package com.nabd.hms.identity.integration;

/** IDC-018: the answer to a Scan & Share, sent to the ABDM gateway after the callback was acknowledged. Only AbdmShareService calls it. */
public interface AbdmShareGateway {

    /** @param tokenNumber the queue token shown in the ABHA app, or null for "please see the desk"; throws ApiException 503 when the gateway cannot be reached */
    void respond(String requestId, Integer tokenNumber);
}
