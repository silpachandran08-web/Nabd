package com.nabd.hms.patient;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.patient.dto.DuplicateSuggestionPage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ACC-030: duplicate suggestions and their dismissal. Merge and undo stay in PatientService (step-up, audit, 30-day window). */
@Service
public class DuplicateSuggestionService {

    private static final int DEFAULT_LIMIT = 20;

    private final DuplicateSuggestionRepository repo;
    private final PatientService patientService;
    private final AuditService audit;
    private final ActorDirectory actors;

    DuplicateSuggestionService(DuplicateSuggestionRepository repo, PatientService patientService, AuditService audit, ActorDirectory actors) {
        this.repo = repo;
        this.patientService = patientService;
        this.audit = audit;
        this.actors = actors;
    }

    @Transactional
    public DuplicateSuggestionPage list(UUID tenantId, UUID callerStaffId, String cursor) {
        patientService.requireVerifiedAndResolveScope(tenantId, callerStaffId);
        UUID[] after = parse(cursor, "cursor", "invalid-cursor");
        UUID afterA = after == null ? new UUID(0, 0) : after[0];
        UUID afterB = after == null ? new UUID(0, 0) : after[1];
        List<DuplicateSuggestionRepository.Hit> hits = repo.page(tenantId, afterA, afterB, DEFAULT_LIMIT + 1);
        String next;
        if (hits.size() > DEFAULT_LIMIT) {
            hits = hits.subList(0, DEFAULT_LIMIT);
            DuplicateSuggestionRepository.Hit last = hits.get(DEFAULT_LIMIT - 1);
            next = last.a() + "_" + last.b();
        } else {
            UUID blockLast = repo.blockLast(tenantId, afterA);
            next = blockLast == null ? null : blockLast + "_" + DuplicateSuggestionRepository.MAX_UUID;
        }
        List<UUID> ids = new ArrayList<>();
        hits.forEach(h -> { ids.add(h.a()); ids.add(h.b()); });
        Map<UUID, DuplicateSuggestionPage.Brief> briefs = repo.briefs(tenantId, ids.stream().distinct().toList());
        return new DuplicateSuggestionPage(hits.stream().map(h -> new DuplicateSuggestionPage.Item(h.a() + "_" + h.b(),
                briefs.get(h.a()), briefs.get(h.b()), h.score(), fields(h))).toList(), next);
    }

    @Transactional
    public com.nabd.hms.patient.dto.RecentMerges recentMerges(UUID tenantId, UUID callerStaffId) {
        patientService.requireVerifiedAndResolveScope(tenantId, callerStaffId);
        List<DuplicateSuggestionRepository.MergeRow> rows = repo.recentMerges(tenantId, 50);
        List<UUID> ids = rows.stream().flatMap(r -> java.util.stream.Stream.of(r.duplicate(), r.survivor())).distinct().toList();
        Map<UUID, DuplicateSuggestionPage.Brief> briefs = repo.briefs(tenantId, ids);
        return new com.nabd.hms.patient.dto.RecentMerges(rows.stream().map(r -> new com.nabd.hms.patient.dto.RecentMerges.Item(r.id(), r.mergedAt(),
                r.mergedAt().plus(30, java.time.temporal.ChronoUnit.DAYS), briefs.get(r.duplicate()), briefs.get(r.survivor()))).toList());
    }

    @Transactional
    public void dismiss(UUID tenantId, UUID callerStaffId, String suggestionId) {
        patientService.requireVerifiedAndResolveScope(tenantId, callerStaffId);
        UUID[] pair = parse(suggestionId, "id", "suggestion-not-found");
        DuplicateSuggestionRepository.Hit h = pair == null ? null : repo.current(tenantId, pair[0], pair[1]);
        if (h == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "suggestion-not-found", "Suggestion not found",
                    "This pair is no longer a duplicate suggestion.");
        }
        repo.dismiss(tenantId, h, callerStaffId);
        ActorDirectory.Actor actor = actors.findActor(tenantId, callerStaffId).orElseThrow();
        audit.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null, "patient.duplicate_dismiss", "patient", h.a(),
                Map.of("patientB", h.b().toString()), Map.of("dismissed", true));
    }

    private static List<String> fields(DuplicateSuggestionRepository.Hit h) {
        List<String> f = new ArrayList<>();
        if (h.phone()) f.add("phone");
        if (h.name()) f.add("name");
        if (h.dob()) f.add("dob");
        return f;
    }

    private static UUID[] parse(String s, String field, String slug) {
        if (s == null || s.isBlank()) return null;
        try {
            String[] p = s.split("_");
            if (p.length != 2) throw new IllegalArgumentException();
            return new UUID[]{UUID.fromString(p[0]), UUID.fromString(p[1])};
        } catch (IllegalArgumentException e) {
            throw new ApiException("invalid-cursor".equals(slug) ? HttpStatus.BAD_REQUEST : HttpStatus.NOT_FOUND, slug,
                    "Invalid " + field, "The " + field + " is malformed.");
        }
    }
}
