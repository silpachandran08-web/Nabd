package com.nabd.hms.shared.notify;

/** Which consent purpose a message needs: care messages = 'messaging', promotions = 'marketing', NONE = staff recipients. */
public enum ConsentClass {
    NONE(null), MESSAGING("messaging"), MARKETING("marketing");

    private final String consentType;

    ConsentClass(String consentType) {
        this.consentType = consentType;
    }

    /** The consents.consent_type to look up, null for NONE. */
    public String consentType() {
        return consentType;
    }
}
