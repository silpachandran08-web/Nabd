package com.nabd.hms.shared.notify;

/**
 * Purpose registry: each purpose fixes its template key, consent class, default priority and channel policy, so a caller
 * names a purpose and never a channel. The code is the stable name stored and sent in events. Template keys equal the
 * code until COM-014 renames them; a tenant must have an approved template of that name in the request's language.
 */
public enum NotificationPurpose {
    APPT_REMINDER_24H("appt_reminder_24h", ConsentClass.MESSAGING, Priority.REMINDER, ChannelPolicy.WHATSAPP_THEN_SMS),
    /** COM-043: two hours before (first name, time); window 45 min. */
    APPT_REMINDER_2H("appt_reminder_2h", ConsentClass.MESSAGING, Priority.REMINDER, ChannelPolicy.WHATSAPP_THEN_SMS),
    /** COM-043: no answer to the day-before reminder by the clinic cutoff (first name, date, time). */
    APPT_NUDGE("appt_nudge", ConsentClass.MESSAGING, Priority.REMINDER, ChannelPolicy.WHATSAPP_THEN_SMS),
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
    /** IDC-014: "your visit instructions are ready" to the patient and record-scope caregivers; 1 param: the patient's first name (no clinical text). */
    VISIT_INSTRUCTIONS("visit_instructions", ConsentClass.MESSAGING, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_THEN_SMS),
    /** IDC-014 (CGV-09): an elderly member missed a booked visit; to caregivers only; 1 param: the member's first name. */
    MISSED_VISIT_ALERT("missed_visit_alert", ConsentClass.MESSAGING, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_THEN_SMS),
    /** IDC-012: age-18 claim invitation to the guardian and the young adult's own number; 2 params: the dependant's first name, the link. */
    PROFILE_CLAIM_INVITE("profile_claim_invite", ConsentClass.NONE, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_ONLY),
    /** ADM-015: the owner's data export is ready (staff recipient, no patient consent); 1 param: the download link (OTP-protected). */
    EXPORT_READY("export_ready", ConsentClass.NONE, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_ONLY),
    /** FIN-020: UPI payment link; slots are clinic name, amount and link only (no line items, no diagnosis). */
    PAYMENT_LINK("payment_link", ConsentClass.MESSAGING, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_THEN_SMS),
    OTP("otp", ConsentClass.NONE, Priority.OTP, ChannelPolicy.WHATSAPP_THEN_SMS),
    /** PLT-012: owner alert (staff recipient, no patient consent). */
    EMERGENCY_ACCESS_USED("emergency_access_used", ConsentClass.NONE, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_ONLY),
    /** ADM-018: owner asks a staff member to finish authenticator enrolment (staff recipient, no params). */
    MFA_ENROL_REMINDER("mfa_enrol_reminder", ConsentClass.NONE, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_THEN_SMS),
    /** ADM-032: licence expiry reminder to the holder and the owners (staff recipients, no patient consent); 4 params: holder, kind, expiry date, days left. */
    LICENCE_EXPIRING("licence_expiring", ConsentClass.NONE, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_ONLY),
    /** ADM-004: the owner is told how many trial days are left (one param: the number; no clinical content). */
    TRIAL_EXPIRING("trial_expiring", ConsentClass.NONE, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_THEN_SMS),
    /** ADM-043: the owner's end-of-day summary (staff recipient); 4 params: day, figures, attention items, overview link. Counts and amounts only. */
    DAILY_SUMMARY("daily_summary", ConsentClass.NONE, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_ONLY),
    /** ADM-005: the owner is stuck on a setup step (staff recipient); 2 params: the step's name, the help link. REMINDER, so quiet hours apply. */
    SETUP_NUDGE("setup_nudge", ConsentClass.NONE, Priority.REMINDER, ChannelPolicy.WHATSAPP_ONLY),
    /** ADM-006: Nabd finished the white-glove setup (staff recipient); 1 param: the steps that were set up. */
    WHITE_GLOVE_SUMMARY("white_glove_summary", ConsentClass.NONE, Priority.TRANSACTIONAL, ChannelPolicy.WHATSAPP_ONLY);

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
