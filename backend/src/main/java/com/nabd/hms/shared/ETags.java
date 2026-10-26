package com.nabd.hms.shared;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.function.Supplier;

/**
 * OPS-006: weak ETag W/"{change_seq}" for single-resource reads of synced tables. The owning service reads the row's
 * change_seq first (one cheap indexed lookup); on a match the controller answers 304 with no body and skips the real
 * read, otherwise it loads the body. A row nobody has touched since V2603 has change_seq NULL, which reads as 0.
 */
public final class ETags {

    private ETags() {
    }

    public static String of(Long changeSeq) {
        return "W/\"" + (changeSeq == null ? 0 : changeSeq) + "\"";
    }

    /** changeSeq == null means the row does not exist for this caller: just run the read, which answers 404. */
    public static <T> ResponseEntity<T> respond(boolean found, Long changeSeq, String ifNoneMatch, Supplier<T> load) {
        if (!found) return ResponseEntity.ok(load.get());
        String etag = of(changeSeq);
        CacheControl cc = CacheControl.noCache().cachePrivate();
        if (ifNoneMatch != null && java.util.Arrays.stream(ifNoneMatch.split(",")).map(String::trim).anyMatch(t -> t.equals(etag) || t.equals("*"))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG, etag).cacheControl(cc).build();
        }
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etag).cacheControl(cc).body(load.get());
    }
}
