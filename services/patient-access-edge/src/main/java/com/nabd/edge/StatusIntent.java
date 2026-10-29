package com.nabd.edge;

import com.nabd.edge.FlowHandler.Conversation;
import com.nabd.edge.FlowHandler.Message;
import com.nabd.edge.FlowHandler.Outcome;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ACC-044: 'status' (English, Hindi, Kannada, Telugu, Arabic) or the {@code status} button answers with the patient's position or time
 * range as the doctor's display mode allows (Core shapes the text, ACC-018). One Core call, so the reply is well inside 3 s. English reply
 * text only (shortcut: translated phrases come with the template catalogue).
 */
@Component
public class StatusIntent implements Intent {

    static final String BUTTON = "status";
    static final Set<String> WORDS = Set.of("status", "स्थिति", "ಸ್ಥಿತಿ", "స్థితి", "الحالة");

    private final CoreClient core;

    StatusIntent(CoreClient core) {
        this.core = core;
    }

    @Override
    public Optional<Outcome> handle(Conversation c, Message m) {
        boolean asked = BUTTON.equals(m.buttonAction()) || (m.buttonAction() == null && m.text() != null && WORDS.contains(normalise(m.text())));
        if (!asked) {
            return Optional.empty();
        }
        List<CoreClient.VisitStatus> visits = core.status(c.tenantId(), c.contactHash());
        if (visits.isEmpty()) {
            return Optional.of(new Outcome(c.state(), c.context(), "You have no visit at the clinic today."));
        }
        String text = visits.stream().map(v -> v.text() != null ? v.text()
                        : "The clinic has not shared a time estimate. Please ask at the desk.")
                .distinct().collect(Collectors.joining("\n"));
        return Optional.of(new Outcome(c.state(), c.context(), text));
    }

    static String normalise(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).trim().toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+$", "");
    }
}
