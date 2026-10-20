package com.nabd.hms.shared.idempotency;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only @Idempotent endpoint: counts executions, can fail once with 500, can be slow. */
@RestController
public class IdempotencyEchoController {
    static final AtomicInteger CALLS = new AtomicInteger();
    static final AtomicBoolean FAIL_NEXT = new AtomicBoolean();

    @Idempotent
    @PostMapping("/v1/test-idem/echo")
    public Map<String, Object> echo(@RequestBody Map<String, Object> body) throws InterruptedException {
        int n = CALLS.incrementAndGet();
        if (FAIL_NEXT.getAndSet(false)) throw new IllegalStateException("boom");
        if (body.containsKey("sleepMs")) Thread.sleep(((Number) body.get("sleepMs")).longValue());
        return Map.of("n", n, "echo", body);
    }

    @PostMapping("/v1/test-idem/plain")
    public Map<String, Object> plain() {
        return Map.of("ok", true);
    }
}
