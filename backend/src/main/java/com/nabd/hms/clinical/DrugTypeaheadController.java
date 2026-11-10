package com.nabd.hms.clinical;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.clinical.dto.DrugSuggestionResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** CLN-020: DOC-033 type-ahead, now over the regional drug master with usage ranking, plus the per-doctor client index. */
@RestController
@RequestMapping("/v1/clinical/drugs")
public class DrugTypeaheadController {

    private final DrugTypeaheadService service;

    DrugTypeaheadController(DrugTypeaheadService service) {
        this.service = service;
    }

    @GetMapping("/search")
    @ClinicalReadScope.NoPatientData
    @PreAuthorize("hasAuthority('clinical:view') or hasAuthority('clinical:edit')")
    public List<DrugSuggestionResponse> search(@CurrentStaff UUID staffId, @RequestParam String q,
                                               @RequestParam(defaultValue = "8") int limit) {
        return service.search(staffId, q, limit);
    }

    /** Gzip-compressed JSON (Content-Encoding is not set: the body is an opaque download the client inflates itself).
     * 304 when If-None-Match is the current ETag. */
    @GetMapping("/client-index")
    @ClinicalReadScope.NoPatientData
    @PreAuthorize("hasAuthority('clinical:view')")
    public ResponseEntity<byte[]> clientIndex(@CurrentStaff UUID staffId,
                                              @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        DrugTypeaheadService.Index index = service.clientIndex(staffId);
        ResponseEntity.BodyBuilder ok = ResponseEntity.status(index.etag().equals(ifNoneMatch) ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .eTag(index.etag()).header(HttpHeaders.CACHE_CONTROL, "private, no-cache");
        return index.etag().equals(ifNoneMatch) ? ok.build()
                : ok.contentType(MediaType.APPLICATION_OCTET_STREAM).header("X-Index-Encoding", "gzip").body(index.body());
    }
}
