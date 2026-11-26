package com.nabd.edge;

import com.nabd.edge.FlowHandler.Button;
import com.nabd.edge.FlowHandler.Conversation;
import com.nabd.edge.FlowHandler.Message;
import com.nabd.edge.FlowHandler.Outcome;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * COM-050: 'My records'. The menu row (or the word 'records') lists the last 10 items of the person on this phone; a guardian or an
 * accepted records proxy first gets one button per person Core says the phone may see. A tap on an item asks Core for an
 * OTP-protected link and answers with the clinic name, the type, the date and the link, nothing else. Core decides every time who
 * may see whom (the Edge shows what Core lists and passes the phone hash); a phone that needs re-verification is stopped earlier by
 * Reverification (COM-041), which treats every message this flow claims as a records request.
 * No state and no context: the whole step is in the signed button (rw:patientRef, rg:itemRef), so it works from any step and a
 * re-run after a version conflict repeats the same Core calls (the link request is keyed by the message id).
 * A row is built from a fixed type word and a parsed date only ({@link #label}), so whatever Core or anyone else sends, no drug,
 * test or diagnosis text can reach a row or the link message. English copy only (shortcut: translated wording comes with the
 * template catalogue, as for ReminderIntent and Reverification).
 */
@Component
class RecordsFlow implements Intent {

    static final String BUTTON = "records";
    static final Set<String> WORDS = Set.of("records", "my records");
    private static final String WHO = "rw:", GET = "rg:";
    /** The only words a row or a link message may show for an item. */
    static final Map<String, String> TYPES = Map.of("visit", "Visit", "prescription", "Prescription", "report", "Report", "invoice", "Invoice");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    /** Lint for a row and for the second line of the link message: "Prescription, 12 Oct" (at most 20 characters, the reply-button limit). */
    static final Pattern LABEL = Pattern.compile("(Visit|Prescription|Report|Invoice), \\d{1,2} [A-Z][a-z]{2}");
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9_.-]{1,37}"); // what a signed button can carry after the 3-character prefix
    private static final Pattern URL = Pattern.compile("https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]{8,400}");
    private static final Pattern FIRST_NAME = Pattern.compile("[\\p{L}\\p{M}'. -]{1,20}");
    static final int ROWS = 10;

    static final String WHO_ASK = "Whose records would you like to see?";
    static final String CHOOSE = "Choose a record to get a secure link.";
    static final String NONE = "There are no records to show yet.";
    static final String NOBODY = "We could not find records for this number. Please ask at the clinic desk.";
    static final String REFUSED = "Those records cannot be shown on this number. Please ask at the clinic desk.";

    private final Http http;
    private final String base;
    private final ObjectMapper json;
    private final ClinicContent.Provider contents;

    RecordsFlow(EdgeProperties p, ObjectMapper json, ClinicContent.Provider contents) {
        this.http = new Http(p.coreTimeoutMs(), p.coreToken());
        this.base = p.coreUrl();
        this.json = json;
        this.contents = contents;
    }

    /** Every message this flow answers; Reverification gates exactly these (COM-041). */
    static boolean claims(Message m) {
        String a = m.buttonAction();
        return a == null ? m.text() != null && WORDS.contains(StatusIntent.normalise(m.text())) : a.equals(BUTTON) || a.startsWith(WHO) || a.startsWith(GET);
    }

    /** "Prescription, 12 Oct" for a type and an ISO date Core sent; null for anything else (the row is then left out). */
    static String label(String type, String isoDate) {
        try {
            String l = TYPES.get(type) + ", " + DAY.format(LocalDate.parse(isoDate));
            return TYPES.containsKey(type) && LABEL.matcher(l).matches() ? l : null;
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }

    @Override
    public Optional<Outcome> handle(Conversation c, Message m) {
        if (!claims(m)) {
            return Optional.empty();
        }
        String a = m.buttonAction() == null ? BUTTON : m.buttonAction();
        try {
            if (a.startsWith(GET)) {
                return Optional.of(say(c, link(c, a.substring(GET.length()), m.messageId()), List.of()));
            }
            if (a.startsWith(WHO)) {
                return Optional.of(list(c, a.substring(WHO.length())));
            }
            List<Button> people = new ArrayList<>();
            for (JsonNode s : core(c, "/households/" + c.contactHash() + "/record-subjects").path("subjects")) {
                String ref = s.path("patientRef").asString(""), name = s.path("firstName").asString("");
                if (REF.matcher(ref).matches() && FIRST_NAME.matcher(name).matches() && people.size() < ROWS) {
                    people.add(new Button(WHO + ref, name));
                }
            }
            return Optional.of(people.isEmpty() ? say(c, NOBODY, List.of())
                    : people.size() == 1 ? list(c, people.get(0).action().substring(WHO.length())) : say(c, WHO_ASK, people));
        } catch (Http.UpstreamStatusException e) {
            if (e.status >= 500) {
                throw e; // an outage: the step fails and the message is retried
            }
            return Optional.of(say(c, REFUSED, List.of())); // Core said no (out of scope, gone, or the number must be verified again)
        }
    }

    private Outcome list(Conversation c, String patientRef) {
        List<Button> rows = new ArrayList<>();
        for (JsonNode i : core(c, "/patients/" + patientRef + "/record-items?limit=" + ROWS + "&channel=" + c.channel() + "&phoneHash=" + c.contactHash()).path("items")) {
            String ref = i.path("itemRef").asString(""), label = label(i.path("type").asString(""), i.path("date").asString(""));
            if (label != null && REF.matcher(ref).matches() && rows.size() < ROWS) {
                rows.add(new Button(GET + ref, label));
            }
        }
        return rows.isEmpty() ? say(c, NONE, List.of()) : say(c, CHOOSE, rows);
    }

    /** The link message: clinic name, type and date, link. Three lines and nothing else. */
    private String link(Conversation c, String itemRef, String messageId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("itemRef", itemRef);
        body.put("recipientPhoneHash", c.contactHash());
        body.put("channel", c.channel());
        JsonNode n = json.readTree(http.post(base + "/internal/v1/tenants/" + c.tenantId() + "/secure-links", json.writeValueAsString(body),
                UUID.nameUUIDFromBytes(("records:" + messageId).getBytes(StandardCharsets.UTF_8)).toString()));
        String label = label(n.path("type").asString(""), n.path("date").asString("")), url = n.path("linkUrl").asString("");
        if (label == null || !URL.matcher(url).matches()) {
            return REFUSED;
        }
        return contents.of(c).profile().clinicName() + "\n" + label + "\n" + url;
    }

    private JsonNode core(Conversation c, String path) {
        return json.readTree(http.get(base + "/internal/v1/tenants/" + c.tenantId() + path));
    }

    /** The step and the context stay as they were: 'My records' can be asked from anywhere. */
    private static Outcome say(Conversation c, String text, List<Button> buttons) {
        return new Outcome(c.state(), c.context(), text, buttons);
    }
}
