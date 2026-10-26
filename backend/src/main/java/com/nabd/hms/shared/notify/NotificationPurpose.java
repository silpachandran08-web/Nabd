package com.nabd.hms.shared.notify;

/**
 * Purpose registry: each purpose fixes its template key, consent class, default priority and channel policy, so a caller
 * names a purpose and never a channel. The code is the stable name stored and sent in events. Template keys equal the
 * code until COM-014 renames them; a tenant must have an approved template of that name in the request's language.
 */
public enum NotificationPurpose {
    APPT_REMINDER_24H("appt_reminder_24h", ConsentClass.MESSAGING, Priority.REMINDER, ChannelPolicy.WHATSAPP_THEN_SMS),
    RECALL_DUE("recall_due", ConsentClass.MESSAGING, Priority.BULK, ChannelPolicy.WHATSAPP_THEN_SMS),
    REVIEW_REQUEST("review_request", ConsentClass.MARKETING, Priority.BULK, ChannelPolicy.WHATSAPP_ONLY),
    SECURE_LINK("secure_link", ConsentClass.MESSAGING, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_THEN_SMS),
    DELAY_NOTICE("delay_notice", ConsentClass.MESSAGING, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_THEN_SMS),
    OTP("otp", ConsentClass.NONE, Priority.OTP, ChannelPolicy.WHATSAPP_THEN_SMS),
    /** PLT-012: owner alert (staff recipient, no patient consent). */
    EMERGENCY_ACCESS_USED("emergency_access_used", ConsentClass.NONE, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_ONLY);

    private final String code;
    private final ConsentClass consentClass;
    private final Priority defaultPriority;
    private final ChannelPolicy channelPolicy;

    NotificationPurpose(String code, ConsentClass consentClass, Priority defaultPriority, ChannelPolicy channelPolicy) {
        this.code = code;
        this.consentClass = consentClass;
        this.defaultPriority = defaultPriority;
        this.channelPolicy = channelPolicy;
    }

    public String code() { return code; }

    public String templateKey() { return code; }

    public ConsentClass consentClass() { return consentClass; }

    public Priority defaultPriority() { return defaultPriority; }

    public ChannelPolicy channelPolicy() { return channelPolicy; }

    public static NotificationPurpose ofCode(String code) {
        for (NotificationPurpose p : values()) {
            if (p.code.equals(code)) {
                return p;
            }
        }
        throw new IllegalArgumentException("unknown notification purpose " + code);
    }
}
