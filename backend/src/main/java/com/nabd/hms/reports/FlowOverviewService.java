package com.nabd.hms.reports;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.reports.FlowOverviewRepository.Day;
import com.nabd.hms.reports.dto.FlowOverviewResponse;
import com.nabd.hms.reports.dto.FlowOverviewResponse.FlowEtaDay;
import com.nabd.hms.reports.dto.FlowOverviewResponse.FlowHourWait;
import com.nabd.hms.reports.dto.FlowOverviewResponse.FlowPunctuality;
import com.nabd.hms.reports.dto.FlowOverviewResponse.FlowTile;
import com.nabd.hms.reports.dto.FlowOverviewResponse.FlowTimeSplit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * ADM-041: the owner's flow overview, computed in memory from at most 60 rollup rows (two statements, both on rollup tables).
 * A range value combines the daily medians weighted by the day's observations.
 * shortcut: a weighted mean of daily medians, not the median of the range; exact range medians need per-visit rows in the read model.
 */
@Service
public class FlowOverviewService {

    private static final Map<String, Integer> RANGE_DAYS = Map.of("today", 1, "d7", 7, "d30", 30);
    private static final int ETA_DAYS = 14;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** A value and the observations behind it; value null = nothing measured. */
    private record Agg(BigDecimal value, long n) {
    }

    private record Metric(String code, String unit, BigDecimal target, Boolean lowerIsBetter, Function<List<Day>, Agg> of) {
    }

    // shortcut: targets are the product defaults of the owner.overview prototype, the same for every clinic; a per-clinic setting when owners ask to tune them
    private static final List<Metric> METRICS = List.of(
            new Metric("wait", "min", BigDecimal.valueOf(15), true, d -> minutes(d, Day::waitS, Day::flowN)),
            new Metric("door_to_doctor", "min", BigDecimal.valueOf(20), true, d -> minutes(d, Day::doorToDoctorS, Day::flowN)),
            new Metric("consult", "min", null, null, d -> minutes(d, Day::consultS, Day::consultN)),
            new Metric("eta_accuracy", "pct", BigDecimal.valueOf(80), false, FlowOverviewService::etaAccuracy),
            new Metric("booking_response", "min", BigDecimal.valueOf(5), true, d -> minutes(d, Day::bookingResponseS, Day::bookingResponseN)),
            new Metric("no_show_rate", "pct", BigDecimal.valueOf(8), true, FlowOverviewService::noShowRate),
            new Metric("visits", "count", null, false, d -> new Agg(d.isEmpty() ? null : BigDecimal.valueOf(d.stream().mapToLong(Day::visits).sum()), d.size())),
            new Metric("revenue", "money", null, false, d -> new Agg(d.isEmpty() ? null
                    : d.stream().map(x -> x.billed().subtract(x.tax())).reduce(BigDecimal.ZERO, BigDecimal::add), d.size())));

    private final FlowOverviewRepository repo;
    private final ClinicClock clock;
    private final ActorDirectory actors;
    private final ObjectMapper json;

    FlowOverviewService(FlowOverviewRepository repo, ClinicClock clock, ActorDirectory actors, ObjectMapper json) {
        this.repo = repo;
        this.clock = clock;
        this.actors = actors;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public FlowOverviewResponse overview(UUID tenantId, String range, UUID facility) {
        Integer days = RANGE_DAYS.get(range);
        if (days == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-range", "Invalid range", "range must be today, d7 or d30.");
        }
        if (facility != null && !facility.equals(tenantId)) {
            throw ApiException.notFound(); // a session works in one facility; another one is not readable from here
        }
        LocalDate to = clock.today(tenantId);
        LocalDate from = to.minusDays(days - 1);
        // today is compared with the same weekday last week, a longer range with the range before it
        LocalDate prevTo = days == 1 ? to.minusDays(7) : from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1);
        LocalDate trendFrom = days == 1 ? to.minusDays(6) : from;
        LocalDate etaFrom = to.minusDays(ETA_DAYS - 1);
        List<Day> all = repo.days(tenantId, prevFrom.isBefore(etaFrom) ? prevFrom : etaFrom, to);
        Map<LocalDate, Day> byDay = new TreeMap<>();
        all.forEach(d -> byDay.put(d.day(), d));
        List<Day> cur = between(all, from, to);
        List<Day> prev = between(all, prevFrom, prevTo);

        List<FlowTile> tiles = new ArrayList<>();
        for (Metric m : METRICS) {
            Agg now = m.of().apply(cur);
            Agg before = m.of().apply(prev);
            List<BigDecimal> trend = new ArrayList<>();
            for (LocalDate d = trendFrom; !d.isAfter(to); d = d.plusDays(1)) {
                Day row = byDay.get(d);
                trend.add(row == null ? null : m.of().apply(List.of(row)).value());
            }
            tiles.add(new FlowTile(m.code(), now.value(), m.unit(), now.value() == null || before.value() == null ? null : now.value().subtract(before.value()),
                    m.target(), m.lowerIsBetter(), now.n(), trend));
        }

        List<FlowEtaDay> eta = new ArrayList<>();
        for (LocalDate d = etaFrom; !d.isAfter(to); d = d.plusDays(1)) {
            Day row = byDay.get(d);
            eta.add(new FlowEtaDay(d, row == null ? null : etaAccuracy(List.of(row)).value(), row == null ? 0 : row.etaN()));
        }
        FlowTimeSplit split = new FlowTimeSplit(minutes(cur, Day::checkinToVitalsS, Day::flowN).value(), minutes(cur, Day::waitS, Day::flowN).value(),
                minutes(cur, Day::consultS, Day::consultN).value(), minutes(cur, Day::checkoutS, Day::consultN).value());

        List<FlowOverviewRepository.DoctorSessions> sessions = repo.punctuality(tenantId, from, to, FlowRollupRepository.PUNCTUAL_WITHIN_S);
        Map<UUID, ActorDirectory.Actor> names = sessions.isEmpty() ? Map.of()
                : actors.findActors(tenantId, sessions.stream().map(FlowOverviewRepository.DoctorSessions::doctorId).toList());
        List<FlowPunctuality> punctuality = sessions.stream().map(s -> new FlowPunctuality(s.doctorId(),
                names.containsKey(s.doctorId()) ? names.get(s.doctorId()).name() : null, s.sessions(), pct(s.onTime(), s.sessions()))).toList();

        Instant refreshedAt = cur.stream().map(Day::refreshedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        String currency = all.stream().map(Day::currency).filter(Objects::nonNull).reduce((a, b) -> b).map(String::trim).orElse(null);
        return new FlowOverviewResponse(range, tenantId, from, to, prevFrom, prevTo, refreshedAt, currency, tiles, waitByHour(byDay.get(to)), eta, split, punctuality);
    }

    private static List<Day> between(List<Day> all, LocalDate from, LocalDate to) {
        return all.stream().filter(d -> !d.day().isBefore(from) && !d.day().isAfter(to)).toList();
    }

    /** Mean of the daily median seconds weighted by the day's observations, in minutes with one decimal. */
    private static Agg minutes(List<Day> days, Function<Day, Integer> seconds, ToIntFunction<Day> weight) {
        long n = 0;
        long sum = 0;
        for (Day d : days) {
            Integer s = seconds.apply(d);
            int w = weight.applyAsInt(d);
            if (s != null && w > 0) {
                n += w;
                sum += (long) s * w;
            }
        }
        return new Agg(n == 0 ? null : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(n * 60), 1, RoundingMode.HALF_UP), n);
    }

    private static Agg etaAccuracy(List<Day> days) {
        long n = 0;
        BigDecimal hits = BigDecimal.ZERO;
        for (Day d : days) {
            if (d.etaPct() != null && d.etaN() > 0) {
                n += d.etaN();
                hits = hits.add(d.etaPct().multiply(BigDecimal.valueOf(d.etaN())));
            }
        }
        return new Agg(n == 0 ? null : hits.multiply(HUNDRED).divide(BigDecimal.valueOf(n), 1, RoundingMode.HALF_UP), n);
    }

    private static Agg noShowRate(List<Day> days) {
        long bookings = days.stream().mapToLong(Day::bookings).sum();
        return new Agg(bookings == 0 ? null : pct(days.stream().mapToLong(Day::noShows).sum(), bookings), bookings);
    }

    private static BigDecimal pct(long part, long whole) {
        return BigDecimal.valueOf(part).multiply(HUNDRED).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    /** wait_by_hour is {"9": 540, ...} (local hour of check-in: median wait seconds), written only by the rollup recompute. */
    private List<FlowHourWait> waitByHour(Day day) {
        if (day == null || day.waitByHourJson() == null) {
            return List.of();
        }
        Map<Integer, FlowHourWait> byHour = new TreeMap<>();
        json.readTree(day.waitByHourJson()).properties().forEach(e -> byHour.put(Integer.parseInt(e.getKey()),
                new FlowHourWait(Integer.parseInt(e.getKey()), BigDecimal.valueOf(e.getValue().asLong()).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP))));
        return List.copyOf(byHour.values());
    }
}
