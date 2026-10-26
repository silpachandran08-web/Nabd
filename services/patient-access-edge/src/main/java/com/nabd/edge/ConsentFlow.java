package com.nabd.edge;

import com.nabd.edge.FlowHandler.Button;
import com.nabd.edge.FlowHandler.Conversation;
import com.nabd.edge.FlowHandler.Message;
import com.nabd.edge.FlowHandler.Outcome;
import com.nabd.edge.FlowHandler.State;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IDC-005 (PAT-01): the consent step of a first-time booker. Two taps at most. Tap 1: agree to care data, or no. Tap 2: updates only,
 * updates and offers, or no updates. Marketing is never pre-selected: it is granted only by the explicit "updates and offers" tap and is
 * relayed as granted=false otherwise. Nothing is sent to Core until tap 2 (a "no" at tap 1 saves nothing and creates no patient record);
 * Core stores the decision before any demographic field exists. The registration flow (T-166) enters the step with {@link #begin}.
 */
@Component
public class ConsentFlow {

    static final String CARE_YES = "consent_care_yes";
    static final String CARE_NO = "consent_care_no";
    static final String UPD_ONLY = "consent_upd_only";
    static final String UPD_OFFERS = "consent_upd_offers";
    static final String UPD_NONE = "consent_upd_none";
    static final String CALLBACK = "callback_request";

    private final CoreClient core;
    private final EdgeProperties props;

    ConsentFlow(CoreClient core, EdgeProperties props) {
        this.core = core;
        this.props = props;
    }

    /** Shows the current notice (fetched from Core, so the version echoed back is the one the patient saw) and asks tap 1. */
    public Outcome begin(Conversation c, String language) {
        String lang = ConsentCopy.supported(language) ? language : ConsentCopy.DEFAULT;
        CoreClient.Notice n = core.currentNotice(c.tenantId(), lang);
        Map<String, String> ctx = new HashMap<>(c.context());
        ctx.put("lang", lang);
        ctx.put("noticeVersion", n.version());
        ConsentCopy.Copy t = ConsentCopy.of(lang);
        return new Outcome(State.CONSENT_CARE, ctx, t.notice().formatted(props.noticeBaseUrl() + n.path()),
                List.of(new Button(CARE_YES, t.agree()), new Button(CARE_NO, t.no())));
    }

    @Component
    static class CareStep implements FlowHandler {
        private final ConsentFlow flow;

        CareStep(ConsentFlow flow) {
            this.flow = flow;
        }

        @Override
        public State state() {
            return State.CONSENT_CARE;
        }

        @Override
        public Outcome handle(Conversation c, Message m) {
            ConsentCopy.Copy t = ConsentCopy.of(c.context().get("lang"));
            if (CARE_YES.equals(m.buttonAction())) {
                Map<String, String> ctx = new HashMap<>(c.context());
                ctx.put("care", "yes");
                return new Outcome(State.CONSENT_UPDATES, ctx, t.updates(),
                        List.of(new Button(UPD_ONLY, t.updOnly()), new Button(UPD_OFFERS, t.updOffers()), new Button(UPD_NONE, t.updNone())));
            }
            if (CARE_NO.equals(m.buttonAction())) {
                return new Outcome(State.DONE, Map.of(), t.declined(), List.of(new Button(CALLBACK, t.callback())));
            }
            return flow.begin(c, c.context().get("lang")); // anything else: ask again (also refreshes the notice version)
        }
    }

    @Component
    static class UpdatesStep implements FlowHandler {
        private final ConsentFlow flow;

        UpdatesStep(ConsentFlow flow) {
            this.flow = flow;
        }

        @Override
        public State state() {
            return State.CONSENT_UPDATES;
        }

        @Override
        public Outcome handle(Conversation c, Message m) {
            String a = m.buttonAction();
            boolean known = UPD_ONLY.equals(a) || UPD_OFFERS.equals(a) || UPD_NONE.equals(a);
            if (!known || !"yes".equals(c.context().get("care")) || m.messageId() == null) {
                return flow.begin(c, c.context().get("lang")); // lost or odd state: start the step again, nothing is stored
            }
            Map<String, Boolean> decisions = new LinkedHashMap<>();
            decisions.put("data_processing", true);
            decisions.put("messaging", !UPD_NONE.equals(a));
            decisions.put("marketing", UPD_OFFERS.equals(a)); // only an explicit tap grants it
            String lang = c.context().getOrDefault("lang", ConsentCopy.DEFAULT);
            // Core is idempotent on the message id, so the engine re-running this step after a version conflict stores nothing twice.
            if (!flow.core.relayConsent(c.tenantId(), c.contactHash(), decisions, c.context().get("noticeVersion"), lang, m.messageId())) {
                return flow.begin(c, lang); // the notice changed while the patient was deciding: show the current one
            }
            Map<String, String> ctx = new HashMap<>();
            ctx.put("lang", lang);
            ctx.put("care", "yes");
            return new Outcome(State.DONE, ctx, ConsentCopy.of(lang).done());
        }
    }
}
