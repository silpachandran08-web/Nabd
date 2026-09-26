package com.nabd.hms.messaging;

import java.util.List;

/** Sends one approved template message; returns Meta's message id (wamid). Throws on rejection. */
public interface WhatsAppClient {

    String sendTemplate(String toPhone, String templateName, String language, List<String> bodyParams);

    /** Authentication templates also take the code as the copy-code button's parameter. */
    String sendOtp(String toPhone, String templateName, String language, String code);

    /** Meta's id for the new template and its initial review status, lower-cased (usually "pending"). */
    record Submission(String metaTemplateId, String status) {
    }

    /** Registers a template on the WhatsApp Business Account. {@code bodyExamples} fill {{1}}..{{n}} for Meta's reviewer. */
    Submission createTemplate(String metaName, String language, String category, String headerText,
                              String body, List<String> bodyExamples);

    /** Replaces a template's text (the fix path for a rejected one); Meta puts it back into review. */
    void editTemplate(String metaTemplateId, String headerText, String body, List<String> bodyExamples);
}
