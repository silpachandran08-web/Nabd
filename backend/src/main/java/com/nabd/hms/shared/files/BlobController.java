package com.nabd.hms.shared.files;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** PLT-029: serves the signed URLs of {@link LocalObjectStorage}. Anonymous by design (the signature is the credential); see FilesBlobSecurityConfig. */
@RestController
class BlobController {

    private final LocalObjectStorage storage;

    BlobController(LocalObjectStorage storage) {
        this.storage = storage;
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
        return storage.get(ObjectStorage.Bucket.valueOf(b), k)
                .map(d -> ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                        .header("X-Content-Type-Options", "nosniff").contentType(MediaType.APPLICATION_OCTET_STREAM).body(d))
                .orElseGet(() -> ResponseEntity.notFound().build());
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
