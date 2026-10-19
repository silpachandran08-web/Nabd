package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.EncounterPage;
import com.nabd.hms.clinical.dto.EncounterResponse;
import com.nabd.hms.clinical.dto.NoteAmendmentResponse;
import com.nabd.hms.clinical.dto.NoteResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** IDC-001 / NB-052: the one place clinical responses drop fields the caller's staff.field_grants exclude. */
public final class FieldGrantMasker {

    public static final String DIAGNOSIS = "diagnosis";

    private FieldGrantMasker() {
    }

    public static NoteResponse note(ReadScope s, NoteResponse n) {
        return s.sees(DIAGNOSIS) ? n : new NoteResponse(n.id(), n.queueEntryId(), n.patientId(), n.doctorId(), n.subjective(),
                n.objective(), n.assessment(), n.plan(), null, n.status(), n.signedAt(), n.updatedAt(), n.signedBy());
    }

    public static List<NoteAmendmentResponse> amendments(ReadScope s, List<NoteAmendmentResponse> list) {
        return s.sees(DIAGNOSIS) ? list : list.stream().map(a -> new NoteAmendmentResponse(a.id(), a.amendedBy(), a.reason(),
                a.subjective(), a.objective(), a.assessment(), a.plan(), null, a.createdAt())).toList();
    }

    public static EncounterPage timeline(ReadScope s, EncounterPage p) {
        return s.sees(DIAGNOSIS) ? p : new EncounterPage(p.data().stream().map(e -> new EncounterResponse(e.queueEntryId(),
                e.occurredAt(), e.doctorId(), null, e.assessment(), e.medications())).toList(), p.page());
    }

    /** Clinical document bodies (visit summary) carry a "diagnosis" entry; it is left out, not blanked. */
    public static Map<String, String> body(ReadScope s, Map<String, String> body) {
        if (s.sees(DIAGNOSIS) || body == null || !body.containsKey(DIAGNOSIS)) {
            return body;
        }
        Map<String, String> out = new LinkedHashMap<>(body);
        out.remove(DIAGNOSIS);
        return out;
    }
}
