package com.nabd.hms.patient.notice;

import com.nabd.hms.common.ApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * IDC-003: the public notice page. Static per (clinic, version, language), no PHI, cacheable at the CDN: a published
 * version never changes, so the response carries an ETag and a long public max-age. Markdown is reduced to headings and
 * paragraphs and HTML-escaped, so a clinic's text can never inject markup.
 */
@RestController
class PublicNoticeController {

    private final PublicNoticeService service;

    PublicNoticeController(PublicNoticeService service) {
        this.service = service;
    }

    @GetMapping("/v1/public/notices/{tenantSlug}/{version}")
    ResponseEntity<String> page(@PathVariable String tenantSlug, @PathVariable String version,
                                @RequestParam(name = "lang", defaultValue = "en") String lang, WebRequest request) {
        if (!lang.matches("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$") || version.length() > 40 || tenantSlug.length() > 80) {
            throw noticeNotFound();
        }
        NoticeRepository.Notice n = service.find(tenantSlug, version, lang).orElseThrow(PublicNoticeController::noticeNotFound);
        String etag = "\"" + NoticeService.hash(java.util.List.of(n.id().toString())) .substring(0, 32) + "\"";
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8)).body(html(n));
    }

    private static ApiException noticeNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "notice-not-found", "Notice not found", "No such notice.");
    }

    static String html(NoticeRepository.Notice n) {
        StringBuilder b = new StringBuilder("<!doctype html><html lang=\"").append(HtmlUtils.htmlEscape(n.language()))
                .append("\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<meta name=\"robots\" content=\"noindex\"><title>Privacy notice</title>")
                .append("<style>body{font:16px/1.6 system-ui,sans-serif;max-width:42rem;margin:0 auto;padding:1rem}</style></head><body>");
        if (!n.legalReviewed()) { // honest marker: the mandatory wording is still a placeholder until the DPO supplies it
            b.append("<p role=\"alert\"><strong>DRAFT: the legal wording of this notice has not been approved yet.</strong></p>");
        }
        for (String block : n.bodyMd().split("\\n\\s*\\n")) {
            String t = block.strip();
            if (t.startsWith("# ")) {
                b.append("<h1>").append(HtmlUtils.htmlEscape(t.substring(2))).append("</h1>");
            } else if (!t.isEmpty()) {
                b.append("<p>").append(HtmlUtils.htmlEscape(t).replace("\n", "<br>")).append("</p>");
            }
        }
        return b.append("<p><small>Version ").append(HtmlUtils.htmlEscape(n.version())).append("</small></p></body></html>").toString();
    }
}
