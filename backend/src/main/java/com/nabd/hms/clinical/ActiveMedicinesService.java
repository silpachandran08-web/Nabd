package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.ActiveMedicinesResponse;
import com.nabd.hms.common.ClinicClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLN-032: which medicines a patient is taken to be on, for the interaction and duplicate-therapy checks and for
 * GET /v1/clinical/patients/{patientId}/active-medicines. Two sources: the lines of signed, not revoked prescriptions
 * whose course has not ended, and patient-reported medicines that are not stopped.
 *
 * Course end of a signed line = the clinic-local signing date plus its duration, once more for every refill allowed
 * (a refill is another course). The end day itself still counts. A duration that cannot be read ("SOS", "till review",
 * blank) has NO known end and the line stays current: a needless warning is preferred to a missed one.
 */
@Service
public class ActiveMedicinesService {

    /** sigPattern is carried for the total-daily-dose sum (null for a reported medicine). */
    record Active(String drugId, String name, String from, LocalDate until, String sigPattern) {
    }

    private static final Pattern DURATION = Pattern.compile(
            "^\\s*(?:x\\s*|for\\s+)?(\\d{1,3})\\s*(d|day|days|w|wk|wks|week|weeks|mo|month|months|yr|year|years)\\s*$", Pattern.CASE_INSENSITIVE);

    private final ActiveMedicinesRepository repo;
    private final ClinicClock clock;

    ActiveMedicinesService(ActiveMedicinesRepository repo, ClinicClock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ActiveMedicinesResponse list(UUID tenantId, UUID patientId) {
        return new ActiveMedicinesResponse(resolve(tenantId, patientId, null, clock.today(tenantId), null).stream()
                .map(a -> new ActiveMedicinesResponse.Item(a.drugId(), a.name(), a.from(), a.until())).toList());
    }

    /** Medicines current on asOf. One entry per product (per name for free text): the one that runs longest.
     * Runs inside the caller's tenant transaction. */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    List<Active> resolve(UUID tenantId, UUID patientId, UUID excludePrescriptionId, LocalDate asOf, Instant signedBefore) {
        ZoneId zone = clock.zone(tenantId);
        Map<String, Active> out = new LinkedHashMap<>();
        for (ActiveMedicinesRepository.SignedLine l : repo.signedLines(tenantId, patientId, excludePrescriptionId, signedBefore)) {
            LocalDate until = courseEnd(l.signedAt().atZone(zone).toLocalDate(), l.duration(), l.refills());
            if (until == null || !until.isBefore(asOf)) {
                keepLongest(out, new Active(l.drugId(), l.name(), "rx", until, l.sigPattern()));
            }
        }
        for (ActiveMedicinesRepository.Reported r : repo.reported(tenantId, patientId, asOf)) {
            keepLongest(out, new Active(r.drugId(), r.name(), "reported", r.stoppedOn(), null));
        }
        return new ArrayList<>(out.values());
    }

    private static void keepLongest(Map<String, Active> out, Active a) {
        String key = a.from() + ":" + (a.drugId() != null ? a.drugId() : "text:" + a.name().trim().toLowerCase(Locale.ROOT));
        Active held = out.get(key);
        if (held == null || (held.until() != null && (a.until() == null || a.until().isAfter(held.until())))) {
            out.put(key, a);
        }
    }

    /** Last day the course counts as current; null when the duration cannot be read. Days, weeks, months and years in
     * calendar arithmetic ("5 days", "2 wk", "x 10 days", "3 months"); nothing else is guessed. */
    static LocalDate courseEnd(LocalDate start, String duration, int refills) {
        Matcher m = DURATION.matcher(duration == null ? "" : duration);
        if (!m.matches()) {
            return null;
        }
        long n = Long.parseLong(m.group(1)) * (1 + Math.max(0, refills));
        if (n <= 0) {
            return null;
        }
        String unit = m.group(2).toLowerCase(Locale.ROOT);
        return unit.startsWith("d") ? start.plusDays(n) : unit.startsWith("w") ? start.plusWeeks(n)
                : unit.startsWith("m") ? start.plusMonths(n) : start.plusYears(n);
    }
}
