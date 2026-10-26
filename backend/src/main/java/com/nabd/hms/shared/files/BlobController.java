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
    ResponseEntity<Void> put(@RequestParam String m, @RequestParam String b, @RequestParam String k, @RequestParam long e,
                             @RequestParam long x, @RequestParam String s, @RequestBody byte[] body) {
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
    ResponseEntity<byte[]> get(@RequestParam String m, @RequestParam String b, @RequestParam String k, @RequestParam long e,
                               @RequestParam long x, @RequestParam String s) {
        if (!"GET".equals(m) || !storage.validSignature(m, b, k, e, x, s) || storage.expired(e)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return storage.get(ObjectStorage.Bucket.valueOf(b), k)
                .map(d -> ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                        .header("X-Content-Type-Options", "nosniff").contentType(MediaType.APPLICATION_OCTET_STREAM).body(d))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
