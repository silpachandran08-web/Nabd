package com.nabd.comms.sms;

import com.nabd.comms.inbox.InboxException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** COM-026.3: the delivery-report webhook proves itself (signature, or allow-listed IP + shared secret) and the internal send API's codes. */
class SmsControllerTest {

    static final String SECRET = "dlr-secret";
    static final String ALLOWED = "203.0.113.10";
    static final byte[] REPORT = "{\"id\":\"bsp-1\",\"status\":\"DELIVRD\"}".getBytes(StandardCharsets.UTF_8);

    final SmsService service = Mockito.mock(SmsService.class);

    @BeforeEach
    void provider() {
        when(service.provider()).thenReturn(new BspSmsProvider("bsp", "http://127.0.0.1:9", "k", JsonMapper.builder().build()));
    }

    MockMvc mvc(String secret, String allowedIps) {
        return MockMvcBuilders.standaloneSetup(new SmsController(service, "test-token", secret, allowedIps, new SimpleMeterRegistry())).build();
    }

    static MockHttpServletRequestBuilder dlr(String ip) {
        return post("/v1/webhooks/sms/bsp").contentType("application/json").content(REPORT).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void aSignedReportIsStoredAndAcked() throws Exception {
        mvc(SECRET, "").perform(dlr("198.51.100.7").header("X-Signature", sign(SECRET, REPORT))).andExpect(status().isOk());
        verify(service).storeDlrs(List.of(new SmsProvider.Dlr("bsp-1", "delivered", null)));
    }

    @Test
    void aForgedReportIsRejected401AndNothingIsStored() throws Exception {
        var m = mvc(SECRET, "");
        m.perform(dlr("198.51.100.7").header("X-Signature", sign("wrong", REPORT))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_SIGNATURE"));
        m.perform(dlr("198.51.100.7")).andExpect(status().isUnauthorized());
        m.perform(dlr("198.51.100.7").header("X-Webhook-Secret", SECRET)).andExpect(status().isUnauthorized()); // the shared secret alone needs an allow-list
        mvc("", "").perform(dlr("198.51.100.7").header("X-Signature", sign("x", REPORT))).andExpect(status().isUnauthorized()); // no secret configured = reject all
        verify(service, never()).storeDlrs(anyList());
    }

    @Test
    void withAnAllowListOnlyListedIpsGetInEvenWhenSigned() throws Exception {
        var m = mvc(SECRET, ALLOWED + ", 203.0.113.11");
        m.perform(dlr("198.51.100.7").header("X-Signature", sign(SECRET, REPORT))).andExpect(status().isUnauthorized());
        m.perform(dlr("198.51.100.7").header("X-Webhook-Secret", SECRET)).andExpect(status().isUnauthorized());
        m.perform(dlr(ALLOWED).header("X-Webhook-Secret", "wrong")).andExpect(status().isUnauthorized());
        verify(service, never()).storeDlrs(anyList());
        m.perform(dlr(ALLOWED).header("X-Webhook-Secret", SECRET)).andExpect(status().isOk()); // a BSP that cannot sign
        m.perform(dlr("203.0.113.11").header("X-Signature", sign(SECRET, REPORT))).andExpect(status().isOk());
    }

    @Test
    void unknownProviderIs404AndASignedNonReportIs400() throws Exception {
        var m = mvc(SECRET, "");
        m.perform(post("/v1/webhooks/sms/other").contentType("application/json").content(REPORT).header("X-Signature", sign(SECRET, REPORT)))
                .andExpect(status().isNotFound());
        byte[] junk = "not json".getBytes(StandardCharsets.UTF_8);
        m.perform(post("/v1/webhooks/sms/bsp").contentType("application/json").content(junk).header("X-Signature", sign(SECRET, junk)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void internalSendNeedsTheServiceTokenAndMapsRefusalsToProblemCodes() throws Exception {
        UUID tenant = UUID.randomUUID();
        String body = "{\"to\":\"+919876543210\",\"text\":\"hello\"}";
        var m = mvc(SECRET, "");
        m.perform(post("/internal/v1/sms/messages").header("X-Tenant-Id", tenant).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        when(service.send(any(), any())).thenThrow(new InboxException(HttpStatus.UNPROCESSABLE_ENTITY, "DLT_TEMPLATE_MISMATCH"));
        m.perform(post("/internal/v1/sms/messages").header("X-Tenant-Id", tenant).header("X-Internal-Token", "test-token").contentType("application/json").content(body))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("DLT_TEMPLATE_MISMATCH"));
        Mockito.reset(service);
        when(service.send(any(), any())).thenReturn(new SmsService.Sent(UUID.randomUUID(), "sent"));
        m.perform(post("/internal/v1/sms/messages").header("X-Tenant-Id", tenant).header("X-Internal-Token", "test-token").contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("sent"));
    }
}
