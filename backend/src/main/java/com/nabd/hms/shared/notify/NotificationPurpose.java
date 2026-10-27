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
    /** ACC-024: the doctor is running late (catalogue template doctor_delay: first name, doctor, new expected time). */
    DOCTOR_DELAY("doctor_delay", ConsentClass.MESSAGING, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_THEN_SMS),
    /** ACC-024: the queue is paused; neutral text, no parameters and never a reason. */
    QUEUE_PAUSED("queue_paused", ConsentClass.MESSAGING, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_THEN_SMS),
    /** FIN-014: dues reminder; the template body names the clinic only (no amounts, no line items). */
    DUES_REMINDER("dues_reminder", ConsentClass.MESSAGING, Priority.REMINDER, ChannelPolicy.WHATSAPP_THEN_SMS),
    /** ACC-006: the clinic moved the patient's appointment (first name, date, time). */
    APPT_RESCHEDULED("appt_rescheduled", ConsentClass.MESSAGING, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_THEN_SMS),
    /** ACC-006: the clinic cancelled the patient's appointment (first name, date, time). */
    APPT_CANCELLED_BY_CLINIC("appt_cancelled_by_clinic", ConsentClass.MESSAGING, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_THEN_SMS),
    /** ACC-043: the desk asks the patient to finish registering in chat (no params, no names in the body). */
    PROFILE_COMPLETION("profile_completion", ConsentClass.MESSAGING, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_ONLY),
    /** IDC-009: consent link to an adult family member's own number (no names in the body; the link is the only parameter). */
    PROXY_INVITE("proxy_invite", ConsentClass.NONE, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_ONLY),
    OTP("otp", ConsentClass.NONE, Priority.OTP, ChannelPolicy.WHATSAPP_THEN_SMS),
    /** PLT-012: owner alert (staff recipient, no patient consent). */
    EMERGENCY_ACCESS_USED("emergency_access_used", ConsentClass.NONE, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_ONLY),
    /** ADM-018: owner asks a staff member to finish authenticator enrolment (staff recipient, no params). */
    MFA_ENROL_REMINDER("mfa_enrol_reminder", ConsentClass.NONE, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_THEN_SMS);

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
