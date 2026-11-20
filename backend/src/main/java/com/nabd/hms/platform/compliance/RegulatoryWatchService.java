package com.nabd.hms.platform.compliance;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.organization.api.RegionRules;
import com.nabd.hms.platform.PlatformAudit;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * PLT-054 (T-330, SSA-11): the DPO's regulatory-watch log (master.regulatory_watch_entries) and the quarterly summary for
 * the founder. Each change carries its market, the impact on Nabd and the tracker task or decision that follows it up;
 * the target is to record it within five working days of noticing it, and a late entry is kept and flagged, not refused.
 * The ritual itself: docs/compliance/regulatory-watch.md.
 */
@Service
public class RegulatoryWatchService {

    static final List<String> SOURCES = List.of("DPDP", "NCNICC", "ZATCA", "TRAI_DLT", "ABDM", "NABIDH", "META", "OTHER");
    static final List<String> IMPACT_LEVELS = List.of("none", "low", "medium", "high");
    static final String ALL_MARKETS = "ALL";
    static final int SLA_WORKING_DAYS = 5;

    @Schema(name = "RegulatoryWatchEntryRequest")
    public record EntryRequest(@NotBlank String source, @NotBlank String market, @NotBlank @Size(min = 3, max = 200) String title,
                               @NotBlank String impactLevel, @NotBlank @Size(min = 3, max = 2000) String impact,
                               @NotBlank @Pattern(regexp = "^(T|DEC)-[0-9]{1,6}$", message = "must be a tracker task (T-123) or a decision (DEC-12)") String linkedRef,
                               @NotNull LocalDate noticedOn) {
    }

    /** workingDays: Monday-to-Friday days from noticedOn to the day it was recorded (UTC). */
    @Schema(name = "RegulatoryWatchEntry")
    public record Entry(UUID id, String source, String market, String title, String impactLevel, String impact, String linkedRef,
                        LocalDate noticedOn, Instant recordedAt, String recordedBy, int workingDays, boolean withinSla) {
    }

    @Schema(name = "RegulatoryWatchLog")
    public record Log(List<String> sources, List<String> markets, List<String> impactLevels, int slaWorkingDays, List<Entry> entries) {
    }

    private final JdbcTemplate jdbc;
    private final RegionRules regions;
    private final OperatorTrainingService training;
    private final PlatformAudit audit;
    private final Clock clock;

    RegulatoryWatchService(JdbcTemplate jdbc, RegionRules regions, OperatorTrainingService training, PlatformAudit audit, Clock clock) {
        this.jdbc = jdbc;
        this.regions = regions;
        this.training = training;
        this.audit = audit;
        this.clock = clock;
    }

    /** shortcut: Monday to Friday, no public-holiday calendar; add one per market if the five-day target is ever audited to the day. */
    static int workingDays(LocalDate from, LocalDate to) {
        int n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) n++;
        }
        return n;
    }

    /** The markets a change can apply to: every region in the regions table, or all of them. */
    private List<String> markets() {
        return Stream.concat(regions.all().stream().map(RegionRules.Region::code), Stream.of(ALL_MARKETS)).toList();
    }

    @Transactional
    public Entry record(UUID operatorId, String ip, EntryRequest req) {
        Instant now = clock.instant();
        if (!SOURCES.contains(req.source()) || !IMPACT_LEVELS.contains(req.impactLevel()) || !markets().contains(req.market())) {
            throw invalid("UNKNOWN_WATCH_VALUE", "Source, market or impact level is not one of the listed values.");
        }
        if (req.noticedOn().isAfter(LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1))) { // a day of slack for the recorder's time zone
            throw invalid("NOTICED_IN_FUTURE", "The day the change was noticed cannot be in the future.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO master.regulatory_watch_entries (id, source, market, title, impact_level, impact, linked_ref, noticed_on, recorded_at, recorded_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)""",
                id, req.source(), req.market(), req.title().strip(), req.impactLevel(), req.impact().strip(), req.linkedRef(), req.noticedOn(), Timestamp.from(now), operatorId);
        audit.record(null, operatorId, ip, "regulatory_watch.recorded", "regulatory_watch_entry", id,
                Map.of("source", req.source(), "market", req.market(), "impactLevel", req.impactLevel(), "linkedRef", req.linkedRef()));
        return entries("WHERE e.id = ?", id).get(0);
    }

    /** Newest first; the whole log when no quarter is given (capped at 500 rows). */
    @Transactional(readOnly = true)
    public Log list(Integer year, Integer quarter) {
        List<Entry> rows = year == null && quarter == null ? entries("")
                : entries("WHERE e.noticed_on BETWEEN ? AND ?", quarterStart(year, quarter), quarterStart(year, quarter).plusMonths(3).minusDays(1));
        return new Log(SOURCES, markets(), IMPACT_LEVELS, SLA_WORKING_DAYS, rows);
    }

    /** The quarter's summary for the founder as Markdown: the changes, the late ones, months with nothing logged and the Nabd team's training state. */
    @Transactional
    public String summary(UUID operatorId, String ip, Integer year, Integer quarter) {
        LocalDate from = quarterStart(year, quarter);
        LocalDate to = from.plusMonths(3).minusDays(1);
        List<Entry> rows = entries("WHERE e.noticed_on BETWEEN ? AND ?", from, to);
        long late = rows.stream().filter(e -> !e.withinSla()).count();
        StringBuilder md = new StringBuilder("# Regulatory watch: summary for " + year + "-Q" + quarter + "\n\n")
                .append("Period ").append(from).append(" to ").append(to).append(". Prepared by the DPO for the founder (docs/compliance/regulatory-watch.md).\n\n")
                .append("## Changes logged: ").append(rows.size()).append("\n\n")
                .append("Recorded within ").append(SLA_WORKING_DAYS).append(" working days: ").append(rows.size() - late).append(". Late: ").append(late).append(".\n\n");
        if (!rows.isEmpty()) {
            md.append("| Noticed | Recorded | Working days | Source | Market | Impact | Change | What it means for Nabd | Linked |\n|---|---|---|---|---|---|---|---|---|\n");
            for (Entry e : rows) {
                md.append("| ").append(e.noticedOn()).append(" | ").append(LocalDate.ofInstant(e.recordedAt(), ZoneOffset.UTC)).append(" | ")
                        .append(e.workingDays()).append(e.withinSla() ? "" : " (late)").append(" | ").append(e.source()).append(" | ").append(e.market()).append(" | ")
                        .append(e.impactLevel()).append(" | ").append(cell(e.title())).append(" | ").append(cell(e.impact())).append(" | ").append(e.linkedRef()).append(" |\n");
            }
            md.append('\n');
        }
        List<String> quiet = new ArrayList<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            YearMonth month = m;
            if (rows.stream().noneMatch(e -> YearMonth.from(e.noticedOn()).equals(month))) quiet.add(m.toString());
        }
        md.append("## Months with no change logged\n\n").append(quiet.isEmpty() ? "None." : String.join(", ", quiet) + ". Confirm the monthly review ran and found nothing.").append("\n\n");
        OperatorTrainingService.Roster team = training.roster();
        md.append("## Security training (Nabd team)\n\n").append(team.current()).append(" of ").append(team.total()).append(" operators hold a current pass of ").append(team.moduleId()).append(".\n");
        team.operators().stream().filter(o -> "overdue".equals(o.state())).forEach(o -> md.append("- Overdue: ").append(cell(o.name())).append(" (").append(o.role()).append(")\n"));
        audit.record(null, operatorId, ip, "regulatory_watch.summary_exported", "regulatory_watch_summary", null, Map.of("year", year, "quarter", quarter, "entries", rows.size()));
        return md.toString();
    }

    private static String cell(String s) {
        return s.replace('|', '/').replaceAll("\\s+", " ");
    }

    private static LocalDate quarterStart(Integer year, Integer quarter) {
        if (year == null || quarter == null || year < 2020 || year > 2100 || quarter < 1 || quarter > 4) {
            throw invalid("INVALID_QUARTER", "Give a year and a quarter from 1 to 4.");
        }
        return LocalDate.of(year, quarter * 3 - 2, 1);
    }

    private static ApiException invalid(String code, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation-failed", "Validation failed", detail, Map.of("code", code));
    }

    private List<Entry> entries(String where, Object... args) {
        return jdbc.query("""
                SELECT e.id, e.source, e.market, e.title, e.impact_level, e.impact, e.linked_ref, e.noticed_on, e.recorded_at, o.name AS recorded_by
                FROM master.regulatory_watch_entries e JOIN master.operators o ON o.id = e.recorded_by
                """ + where + " ORDER BY e.noticed_on DESC, e.recorded_at DESC LIMIT 500",
                (rs, i) -> {
                    LocalDate noticed = rs.getObject("noticed_on", LocalDate.class);
                    Instant recorded = rs.getTimestamp("recorded_at").toInstant();
                    int days = workingDays(noticed, LocalDate.ofInstant(recorded, ZoneOffset.UTC));
                    return new Entry(rs.getObject("id", UUID.class), rs.getString("source"), rs.getString("market"), rs.getString("title"), rs.getString("impact_level"),
                            rs.getString("impact"), rs.getString("linked_ref"), noticed, recorded, rs.getString("recorded_by"), days, days <= SLA_WORKING_DAYS);
                }, args);
    }
}
