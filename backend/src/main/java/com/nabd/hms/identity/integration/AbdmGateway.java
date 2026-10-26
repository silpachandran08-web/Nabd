package com.nabd.hms.identity.integration;

/**
 * IDC-016: the port to the ABDM gateway, HFR and HPR. Only identity.abdm.AbdmService calls it, and only after the per-clinic
 * switch is checked (DEC-33). The real client is {@link HttpAbdmGateway}; tests use a recorded-fixture HTTP server or a fake.
 */
public interface AbdmGateway {

    /** @return true when HFR knows the facility id, false on "not found"; throws ApiException 503 when the registry cannot answer */
    boolean facilityExists(String hfrId);

    /** @return true when HPR knows the professional id; same failure rules */
    boolean professionalExists(String hprId);
}
