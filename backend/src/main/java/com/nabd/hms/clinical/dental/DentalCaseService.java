package com.nabd.hms.clinical.dental;

import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.ChairNoteItem;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.ChairNoteRequest;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.ImageItem;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.ImageRequest;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.LaLot;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.Preset;
import com.nabd.hms.clinical.dental.dto.DentalCaseDtos.SettingsResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.shared.FileStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CLN-050 (DENT-01/02/03): clinic numbering preference (display only, storage stays FDI), the tooth/case image library on top
 * of the PLT-029/030 upload pipeline, and chair-side preset notes (clinic list, a dentist's own presets merged over it by code).
 */
@Service
public class DentalCaseService {

    /** The spec's examples, used until the clinic saves its own list. NEEDS CLINICAL REVIEW (wording and the LA drug name). */
    static final List<Preset> DEFAULT_PRESETS = List.of(
            new Preset("RCT_STARTED", "RCT started", false, null),
            new Preset("LA_GIVEN", "LA given", true, "lignocaine"),
            new Preset("NEXT_OBTURATION", "Next: obturation", false, null));

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png");

    private final DentalCaseRepository repo;
    private final FileStore files;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final ClinicClock clock;
    private final ObjectMapper json;

    DentalCaseService(DentalCaseRepository repo, FileStore files, AuditService audit, ActorDirectory actors, ClinicClock clock, ObjectMapper json) {
        this.repo = repo;
        this.files = files;
        this.audit = audit;
        this.actors = actors;
        this.clock = clock;
        this.json = json;
    }

    /** FDI permanent teeth: quadrant 1-4, position 1-8. */
    static boolean validFdi(int n) {
        return n / 10 >= 1 && n / 10 <= 4 && n % 10 >= 1 && n % 10 <= 8;
    }

    /** The caller's taps: the clinic list, each preset replaced by the caller's own one with the same code, then the caller's new codes. */
    static List<Preset> merge(List<Preset> clinic, List<Preset> mine) {
        Map<String, Preset> out = new LinkedHashMap<>();
        clinic.forEach(p -> out.put(p.code(), p));
        mine.forEach(p -> out.put(p.code(), p));
        return List.copyOf(out.values());
    }

    @Transactional(readOnly = true)
    public SettingsResponse settings(UUID tenantId, UUID staffId) {
        DentalCaseRepository.SettingsRow row = repo.settings(tenantId).orElse(null);
        List<Preset> clinic = row == null || row.presetsJson() == null ? DEFAULT_PRESETS : read(row.presetsJson());
        List<Preset> mine = repo.myPresets(tenantId, staffId).map(this::read).orElse(List.of());
        return new SettingsResponse(row == null ? "FDI" : row.numbering(), clinic, mine, merge(clinic, mine));
    }

    @Transactional
    public SettingsResponse updateSettings(UUID tenantId, UUID staffId, String ip, String numbering, List<Preset> presets) {
        if (presets != null) requireUniqueCodes(presets);
        SettingsResponse before = settings(tenantId, staffId);
        repo.upsertSettings(tenantId, numbering, presets == null ? null : write(presets), staffId);
        SettingsResponse after = settings(tenantId, staffId);
        record(tenantId, staffId, ip, "dental_settings.update", "dental_settings", tenantId,
                Map.of("numbering", before.numbering(), "presetCodes", codes(before.clinicPresets())),
                Map.of("numbering", after.numbering(), "presetCodes", codes(after.clinicPresets())));
        return after;
    }

    /** An empty list removes the dentist's own presets (back to the clinic list). */
    @Transactional
    public SettingsResponse updateMyPresets(UUID tenantId, UUID staffId, String ip, List<Preset> presets) {
        requireUniqueCodes(presets);
        if (presets.isEmpty()) repo.clearMyPresets(tenantId, staffId);
        else repo.saveMyPresets(tenantId, staffId, write(presets));
        record(tenantId, staffId, ip, "dental_presets.update", "dental_preset_overrides", staffId, null, Map.of("presetCodes", codes(presets)));
        return settings(tenantId, staffId);
    }

    /** The caller has passed the IDC-001 read gate for this patient. */
    @Transactional
    public UUID attachImage(UUID tenantId, UUID staffId, String ip, UUID patientId, ImageRequest req) {
        if (!repo.visitOfPatient(tenantId, req.queueEntryId(), patientId)) throw ApiException.notFound();
        List<Integer> teeth = teeth(req.teeth());
        FileStore.FileInfo f = files.info(req.uploadId()).orElseThrow(ApiException::notFound);
        if (!"available".equals(f.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UPLOAD_NOT_CLEAN", "Upload not clean",
                    "The upload has not passed the scan yet, or was blocked. Wait for 'Ready' or upload it again.", Map.of("code", "UPLOAD_NOT_CLEAN"));
        }
        if (!IMAGE_TYPES.contains(f.contentType())) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "TYPE_NOT_ALLOWED", "Type not allowed", "Only JPEG and PNG images can be attached to the chart.");
        }
        if (!files.bind(req.uploadId(), patientId, req.queueEntryId())) {
            throw new ApiException(HttpStatus.CONFLICT, "FILE_NOT_AVAILABLE", "File not available", "The file belongs to another patient.");
        }
        UUID id = repo.insertImage(tenantId, patientId, req.queueEntryId(), req.uploadId(), req.kind(), teeth, staffId).orElse(null);
        if (id == null) return repo.imageByUpload(tenantId, patientId, req.uploadId()).orElseThrow(ApiException::notFound); // replay
        record(tenantId, staffId, ip, "dental_image.attach", "dental_images", id, null,
                Map.of("kind", req.kind(), "teeth", teeth, "fileId", req.uploadId().toString()));
        return id;
    }

    @Transactional(readOnly = true)
    public List<ImageItem> images(UUID tenantId, UUID patientId, Integer tooth) {
        return repo.images(tenantId, patientId, tooth);
    }

    /** The caller has passed the IDC-001 read gate for this patient. */
    @Transactional
    public UUID addChairNote(UUID tenantId, UUID staffId, String ip, UUID patientId, ChairNoteRequest req) {
        if (!repo.visitOfPatient(tenantId, req.queueEntryId(), patientId)) throw ApiException.notFound();
        if (!validFdi(req.tooth())) throw invalidTooth();
        Preset preset = settings(tenantId, staffId).presets().stream().filter(p -> p.code().equals(req.presetCode())).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_PRESET", "Unknown preset",
                        "This chair-side phrase is not in your list any more. Reopen the chart to refresh it.", Map.of("code", "UNKNOWN_PRESET")));
        String text = preset.label();
        UUID drugId = null;
        String lot = null;
        if (req.laBatch() != null) {
            if (!preset.la()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "LA_NOT_EXPECTED", "No anaesthetic on this phrase", "Only an anaesthetic phrase records a drug and lot.");
            }
            LaLot l = repo.usableLot(tenantId, clock.today(tenantId), req.laBatch().drugId(), req.laBatch().lot().strip())
                    .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "LA_LOT_UNKNOWN", "Lot not usable",
                            "This lot is not in pharmacy stock, is used up or has expired. Pick another lot.", Map.of("code", "LA_LOT_UNKNOWN")));
            drugId = l.drugId();
            lot = l.lot();
            text = text + " · " + l.drugName() + " · lot " + l.lot();
        }
        UUID id = repo.insertNote(tenantId, patientId, req.queueEntryId(), req.tooth(), preset.code(), text, drugId, lot, staffId);
        Map<String, Object> after = new LinkedHashMap<>(Map.of("tooth", req.tooth(), "presetCode", preset.code()));
        if (drugId != null) after.put("laDrugId", drugId.toString());
        record(tenantId, staffId, ip, "dental_chair_note.create", "dental_chair_notes", id, null, after);
        return id;
    }

    @Transactional(readOnly = true)
    public List<ChairNoteItem> chairNotes(UUID tenantId, UUID patientId, Integer tooth) {
        return repo.notes(tenantId, patientId, tooth);
    }

    @Transactional(readOnly = true)
    public List<LaLot> laLots(UUID tenantId, String query) {
        String q = query == null ? "" : query.strip();
        if (q.length() > 60) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-query", "Invalid query", "q is at most 60 characters.");
        return q.length() < 2 ? List.of() : repo.usableLots(tenantId, clock.today(tenantId), q, 20);
    }

    private List<Integer> teeth(List<Integer> in) {
        if (in == null) return List.of();
        if (in.stream().anyMatch(n -> !validFdi(n))) throw invalidTooth();
        return in.stream().distinct().sorted().toList();
    }

    private static ApiException invalidTooth() {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid-tooth", "Invalid tooth number",
                "Tooth numbers are FDI two-digit codes (11-18, 21-28, 31-38, 41-48).");
    }

    private static void requireUniqueCodes(List<Preset> presets) {
        Set<String> seen = new HashSet<>();
        for (Preset p : presets) {
            if (!seen.add(p.code())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "duplicate-preset", "Duplicate preset", "Preset code " + p.code() + " appears twice.");
            }
        }
    }

    private static List<String> codes(List<Preset> presets) {
        return presets.stream().map(Preset::code).toList();
    }

    private void record(UUID tenantId, UUID staffId, String ip, String action, String entity, UUID entityId, Object before, Object after) {
        ActorDirectory.Actor a = actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
        audit.record(tenantId, "staff", staffId, a.name(), a.role(), ip, action, entity, entityId, before, after);
    }

    private List<Preset> read(String s) {
        try {
            return json.readValue(s, new TypeReference<List<Preset>>() { });
        } catch (JacksonException e) {
            throw new IllegalStateException("stored dental presets are unreadable", e);
        }
    }

    private String write(List<Preset> presets) {
        try {
            return json.writeValueAsString(presets);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("dental presets are not serializable", e);
        }
    }
}
