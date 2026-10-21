package com.nabd.hms.shared.relay;

/** RelayArchitectureTest fixture: relay code that calls an HTTP API, which the rule must reject. Never instantiated. */
public class HttpCallingRelayFixture {
    java.net.http.HttpClient client() {
        return java.net.http.HttpClient.newHttpClient();
    }
}
