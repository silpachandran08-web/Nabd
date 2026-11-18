package com.nabd.hms.shared.files;

import com.nabd.hms.common.TenantScope;
import com.nabd.hms.shared.HiddenFiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PLT-029: serves the signed URLs of {@link LocalObjectStorage}. Anonymous by design (the signature is the credential); see FilesBlobSecurityConfig. */
@RestController
class BlobController {

    /** Every clean object is tenant/{tenantId}/{fileId} (FileService). */
    private static final Pattern CLEAN_KEY = Pattern.compile("tenant/([0-9a-f-]{36})/([0-9a-f-]{36})");

    private final LocalObjectStorage storage;
    private final TenantScope tenantScope;
    private final List<HiddenFiles> hiddenFiles;

    BlobController(LocalObjectStorage storage, TenantScope tenantScope, List<HiddenFiles> hiddenFiles) {
        this.storage = storage;
        this.tenantScope = tenantScope;
        this.hiddenFiles = hiddenFiles;
    }

    @PutMapping(LocalObjectStorage.PATH)
    ResponseEntity<Void> put(@RequestParam(required = false) String m, @RequestParam(required = false) String b, @RequestParam(required = false) String k,
                             @RequestParam(required = false) Long e, @RequestParam(required = false) Long x, @RequestParam(required = false) String s,
                             @RequestBody(required = false) byte[] body) {
        if (unsigned(m, b, k, e, x, s)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (body == null) {
            body = new byte[0];
        }
        if (!"PUT".equals(m) || !storage.validSignature(m, b, k, e, x, s) || storage.expired(e)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (body.length > x) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        storage.put(ObjectStorage.Bucket.valueOf(b), k, body);
        return ResponseEntity.ok().build();
    }

    @GetMapping(LocalObjectStorage.PATH)
    ResponseEntity<byte[]> get(@RequestParam(required = false) String m, @RequestParam(required = false) String b, @RequestParam(required = false) String k,
                               @RequestParam(required = false) Long e, @RequestParam(required = false) Long x, @RequestParam(required = false) String s) {
        if (unsigned(m, b, k, e, x, s)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!"GET".equals(m) || !storage.validSignature(m, b, k, e, x, s) || storage.expired(e)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (hidden(b, k)) {
            return ResponseEntity.notFound().build(); // same answer as an object that does not exist
        }
        return storage.get(ObjectStorage.Bucket.valueOf(b), k)
                .map(d -> ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                        .header("X-Content-Type-Options", "nosniff").contentType(MediaType.APPLICATION_OCTET_STREAM).body(d))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * T1-BUG-4: the signature proves the link was issued, not that the file may still be shown. Asked on every read, so a link
     * issued before a photo-consent withdrawal stops serving at once. A failing check fails the request (no bytes).
     */
    private boolean hidden(String bucket, String key) {
        Matcher m = CLEAN_KEY.matcher(key);
        if (!ObjectStorage.Bucket.CLEAN.name().equals(bucket) || !m.matches()) {
            return false; // not a stored file (file_objects keys always match), so nothing can have hidden it
        }
        UUID tenantId = UUID.fromString(m.group(1));
        UUID fileId = UUID.fromString(m.group(2));
        return tenantScope.run(tenantId, () -> hiddenFiles.stream().anyMatch(h -> h.hidden(tenantId, fileId)));
    }

    /** No signature material at all (e.g. a bearer token instead): 401, never a 400 that hints at the parameters. */
    private static boolean unsigned(Object... p) {
        for (Object o : p) {
            if (o == null) {
                return true;
            }
        }
        return false;
    }
}
