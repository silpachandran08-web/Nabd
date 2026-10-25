package com.nabd.hms.realtime;

import com.nabd.hms.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ACC-021: GET /v1/events, the staff screens' server-sent event stream. Topics are checked against the caller's
 * authorities at subscribe (403 TOPIC_FORBIDDEN names the topic and nothing is subscribed); the connection holds no
 * database transaction. The stream ends when the access token expires, the client reconnects with the new one.
 */
@RestController
class EventsController {

    private final RealtimeHub hub;

    EventsController(RealtimeHub hub) {
        this.hub = hub;
    }

    @GetMapping(value = "/v1/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal Jwt jwt, Authentication auth,
                                      @RequestParam(required = false) String topics,
                                      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        Set<String> granted = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        Set<RealtimeTopic> wanted = EnumSet.noneOf(RealtimeTopic.class);
        if (topics == null || topics.isBlank()) {
            for (RealtimeTopic t : RealtimeTopic.values()) {
                if (granted.contains(t.permission())) {
                    wanted.add(t);
                }
            }
        } else {
            for (String name : topics.split(",")) {
                RealtimeTopic t = RealtimeTopic.parse(name.trim()).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "INVALID_TOPIC", "Unknown topic", "Topics are queue, nursing, doctor-status, requests, exceptions, messages."));
                wanted.add(t);
            }
        }
        for (RealtimeTopic t : wanted) {
            if (!granted.contains(t.permission())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "TOPIC_FORBIDDEN", "Topic not allowed",
                        "You do not have access to the topic " + t.wire() + ".");
            }
        }
        if (wanted.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TOPIC_FORBIDDEN", "Topic not allowed", "You do not have access to any topic.");
        }
        SseEmitter emitter = new SseEmitter(0L);
        RealtimeConnection[] holder = new RealtimeConnection[1];
        RealtimeConnection c = hub.open(UUID.fromString(jwt.getClaimAsString("tenantId")), UUID.fromString(jwt.getSubject()),
                sessionId(jwt), wanted, jwt.getExpiresAt() == null ? null : Instant.from(jwt.getExpiresAt()), lastEventId,
                new RealtimeConnection.Sink() {
                    @Override
                    public void send(String event, String id, String data) throws IOException {
                        SseEmitter.SseEventBuilder b = SseEmitter.event().name(event).data(data);
                        emitter.send(id == null ? b : b.id(id));
                    }

                    @Override
                    public void complete() {
                        emitter.complete();
                    }
                });
        holder[0] = c;
        Runnable done = () -> hub.unregister(holder[0]);
        emitter.onCompletion(done);
        emitter.onTimeout(done);
        emitter.onError(e -> done.run());
        // X-Accel-Buffering keeps nginx-style proxies from buffering the stream
        return ResponseEntity.ok().header("X-Accel-Buffering", "no").header("Cache-Control", "no-cache, no-transform").body(emitter);
    }

    private static UUID sessionId(Jwt jwt) {
        String sid = jwt.getClaimAsString("sid");
        return sid == null ? null : UUID.fromString(sid);
    }
}
