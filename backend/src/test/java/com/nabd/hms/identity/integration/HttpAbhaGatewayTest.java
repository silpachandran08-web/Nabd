package com.nabd.hms.identity.integration;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.shared.integration.ExternalCallGuard;
import com.nabd.hms.shared.integration.IntegrationProperties;
import com.nabd.hms.shared.integration.ResilientHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IDC-017 client contract against a local stand-in for ABDM M1 (NOT a recording of the sandbox: Needs external action, DEP-05).
 * Proves: Aadhaar, ABHA id and OTP leave the process only RSA-encrypted with the key the gateway publishes; the Aadhaar number
 * appears in no request in clear; ABDM refusals are 422; blank paths fail closed.
 */
class HttpAbhaGatewayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private KeyPair keys;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int verifyStatus = 200;

    @BeforeEach
    void start() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/hiecm/gateway/v3/sessions", ex -> json(ex, 200, "{\"accessToken\":\"tok\",\"expiresIn\":1200}"));
        server.createContext("/abha/cert", ex -> json(ex, 200, "{\"publicKey\":\"-----BEGIN PUBLIC KEY-----\\n" + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()) + "\\n-----END PUBLIC KEY-----\"}"));
        server.createContext("/abha/enrol/otp", ex -> { bodies.add(body(ex)); json(ex, 200, "{\"txnId\":\"T-ENROL\"}"); });
        server.createContext("/abha/enrol", ex -> { bodies.add(body(ex)); json(ex, 200, "{\"ABHAProfile\":{\"ABHANumber\":\"91-1234-5678-9012\",\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"yearOfBirth\":\"1990\",\"monthOfBirth\":\"3\",\"dayOfBirth\":\"9\",\"gender\":\"F\",\"mobile\":\"9800000001\",\"preferredAbhaAddress\":\"asha@abdm\"}}"); });
        server.createContext("/abha/login/otp", ex -> { bodies.add(body(ex)); json(ex, 200, "{\"txnId\":\"T-LOGIN\"}"); });
        server.createContext("/abha/login/verify", ex -> { bodies.add(body(ex)); json(ex, verifyStatus, verifyStatus == 200 ? "{\"ABHAProfile\":{\"ABHANumber\":\"91123456789012\",\"name\":\"Asha Rao\",\"dob\":\"1990-03-09\",\"gender\":\"F\"}}" : "{}"); });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void json(HttpExchange ex, int status, String b) throws IOException {
        byte[] bytes = b.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private AbhaGateway gateway(boolean configured) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        AbdmProperties props = new AbdmProperties(base, null, null, "", "", "", null, null);
        IntegrationProperties ip = new IntegrationProperties(500, 1000, 5, 30, Map.of());
        ResilientHttpClient http = new ResilientHttpClient(ip, new ExternalCallGuard(new MockEnvironment()), Clock.systemUTC());
        HttpAbdmGateway abdm = new HttpAbdmGateway(props, http, Clock.systemUTC(), n -> Optional.of("x"));
        return new HttpAbhaGateway(abdm, configured ? new AbhaProperties("/abha/cert", "/abha/login/otp", "/abha/login/verify", "/abha/enrol/otp", "/abha/enrol")
                : new AbhaProperties("", "", "", "", ""));
    }

    private String decrypt(String b64) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        c.init(Cipher.DECRYPT_MODE, keys.getPrivate());
        return new String(c.doFinal(Base64.getDecoder().decode(b64)), StandardCharsets.UTF_8);
    }

    @Test
    void aadhaarEnrolSendsOnlyCiphertextAndParsesTheProfile() throws Exception {
        AbhaGateway g = gateway(true);
        assertThat(g.enrolOtp(AbhaGateway.EnrolMode.AADHAAR, "234567890123")).isEqualTo("T-ENROL");
        JsonNode otpReq = JSON.readTree(bodies.get(0));
        assertThat(bodies.get(0)).doesNotContain("234567890123");
        assertThat(otpReq.path("loginHint").asString()).isEqualTo("aadhaar");
        assertThat(decrypt(otpReq.path("loginId").asString())).isEqualTo("234567890123");

        AbhaGateway.Profile p = g.enrolConfirm(AbhaGateway.EnrolMode.AADHAAR, "T-ENROL", "123456", "9800000001");
        assertThat(bodies.get(1)).doesNotContain("123456\"");
        assertThat(decrypt(JSON.readTree(bodies.get(1)).path("authData").path("otp").path("otpValue").asString())).isEqualTo("123456");
        assertThat(p.abhaNumber()).isEqualTo("91-1234-5678-9012");
        assertThat(p.abhaAddress()).isEqualTo("asha@abdm");
        assertThat(p.name()).isEqualTo("Asha Rao");
        assertThat(p.dob()).isEqualTo("1990-03-09");
    }

    @Test
    void loginByNumberAndByAddressUseTheRightHintAndEncryptTheId() throws Exception {
        AbhaGateway g = gateway(true);
        assertThat(g.loginOtp("91-1234-5678-9012")).isEqualTo("T-LOGIN");
        assertThat(g.loginOtp("asha@abdm")).isEqualTo("T-LOGIN");
        assertThat(JSON.readTree(bodies.get(0)).path("loginHint").asString()).isEqualTo("abha-number");
        assertThat(JSON.readTree(bodies.get(1)).path("loginHint").asString()).isEqualTo("abha-address");
        assertThat(decrypt(JSON.readTree(bodies.get(1)).path("loginId").asString())).isEqualTo("asha@abdm");
        assertThat(g.loginConfirm("T-LOGIN", "654321").abhaNumber()).isEqualTo("91123456789012");
    }

    @Test
    void abdmRefusingTheCodeIs422AndBlankConfigurationFailsClosed() {
        verifyStatus = 400;
        assertThatThrownBy(() -> gateway(true).loginConfirm("T", "111111")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.typeSlug()).isEqualTo("abha-invalid"));
        assertThatThrownBy(() -> gateway(false).loginOtp("91123456789012")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.typeSlug()).isEqualTo("abdm-not-configured"));
        server.stop(0);
        assertThatThrownBy(() -> gateway(true).loginOtp("91123456789012")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.typeSlug()).isEqualTo("abdm-unavailable"));
    }
}
