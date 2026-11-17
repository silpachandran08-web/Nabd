package com.nabd.edge;

import com.nabd.edge.FlowHandler.Button;
import com.nabd.edge.FlowHandler.Conversation;
import com.nabd.edge.FlowHandler.Message;
import com.nabd.edge.FlowHandler.Outcome;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * COM-025: 'Settings' (in any offered language) or the {@code settings} button shows the message languages as buttons; a tap saves
 * the choice at Core for everyone on this phone (no OTP: a language is not PHI), remembers it for the bot, and confirms in the new
 * language. Works from any step and leaves the step where it was. Re-runnable: Core's save is idempotent.
 */
@Component
public class SettingsIntent implements Intent {

    static final String BUTTON = "settings";
    static final String PREFIX = "setlang:";
    static final Set<String> WORDS = Set.of("settings", "setting", "सेटिंग्स", "सेटिंग", "ಸೆಟ್ಟಿಂಗ್ಸ್", "సెట్టింగ్స్", "الإعدادات", "اعدادات");

    private final CoreClient core;
    private final ContactLanguage languages;
    private final EdgeProperties props;

    SettingsIntent(CoreClient core, ContactLanguage languages, EdgeProperties props) {
        this.core = core;
        this.languages = languages;
        this.props = props;
    }

    @Override
    public Optional<Outcome> handle(Conversation c, Message m) {
        String a = m.buttonAction();
        if (a != null && a.startsWith(PREFIX)) {
            String lang = a.substring(PREFIX.length());
            if (!offered().contains(lang)) {
                return Optional.of(menu(c)); // a language this region no longer offers: show the choices again
            }
            try {
                core.saveLanguage(c.tenantId(), c.contactHash(), lang, "settings:" + m.messageId());
            } catch (Http.UpstreamStatusException e) {
                if (e.status != 404) {
                    throw e;
                }
                // no patient record on this phone yet: the bot still answers in the chosen language from now on
            }
            languages.save(c.tenantId(), c.channel(), c.contactHash(), lang);
            Map<String, String> ctx = new HashMap<>(c.context());
            ctx.put("lang", lang);
            return Optional.of(new Outcome(c.state(), ctx, BookingCopy.t(lang, "settings.saved", BookingCopy.NAMES.get(lang))));
        }
        boolean asked = BUTTON.equals(a) || (a == null && m.text() != null && WORDS.contains(StatusIntent.normalise(m.text())));
        return asked ? Optional.of(menu(c)) : Optional.empty();
    }

    private Outcome menu(Conversation c) {
        String lang = c.context().get("lang");
        if (!BookingCopy.supported(lang)) {
            lang = languages.find(c.tenantId(), c.channel(), c.contactHash()).orElse("en");
        }
        return new Outcome(c.state(), c.context(), BookingCopy.t(lang, "settings.ask"),
                offered().stream().map(l -> new Button(PREFIX + l, BookingCopy.NAMES.get(l))).toList());
    }

    private List<String> offered() {
        List<String> l = props.languages().getOrDefault(props.region().toLowerCase(Locale.ROOT), List.of("en")).stream().filter(BookingCopy::supported).toList();
        return l.isEmpty() ? List.of("en") : l;
    }
}
