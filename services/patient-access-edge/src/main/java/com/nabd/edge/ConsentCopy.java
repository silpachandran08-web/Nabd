package com.nabd.edge;

import java.util.Map;

/**
 * IDC-005: the words of the consent step in en / hi / kn / te. No name, number or health detail is ever part of a message; the only
 * variable is the notice link. Needs external action: native-speaker and legal review of hi / kn / te, and the Meta utility-template
 * registration for conversations the clinic opens (a patient-initiated chat needs no template).
 */
final class ConsentCopy {

    record Copy(String notice, String agree, String no, String updates, String updOnly, String updOffers, String updNone, String declined,
                String callback, String done) {
    }

    static final String DEFAULT = "en";

    private static final Map<String, Copy> BY_LANG = Map.of(
            "en", new Copy(
                    "Before we save your details, please read our privacy notice: %s. Do you agree that the clinic may keep your health data to look after you?",
                    "I agree", "No thanks",
                    "Can we send you appointment updates on WhatsApp? Offers are separate and stay off unless you choose them.",
                    "Updates only", "Updates and offers", "No updates",
                    "No problem. We have not saved anything. Tap below if you would like the clinic to call you back.", "Call me back",
                    "Thank you. Your choices are saved."),
            "hi", new Copy(
                    "आपकी जानकारी सहेजने से पहले, कृपया हमारी गोपनीयता सूचना पढ़ें: %s। क्या आप सहमत हैं कि क्लिनिक आपकी देखभाल के लिए आपका स्वास्थ्य डेटा रखे?",
                    "मैं सहमत हूँ", "नहीं, धन्यवाद",
                    "क्या हम आपको WhatsApp पर अपॉइंटमेंट अपडेट भेज सकते हैं? ऑफ़र अलग हैं और तब तक बंद रहेंगे जब तक आप न चुनें।",
                    "सिर्फ़ अपडेट", "अपडेट और ऑफ़र", "कोई अपडेट नहीं",
                    "कोई बात नहीं। हमने कुछ भी सहेजा नहीं है। क्लिनिक से कॉल-बैक चाहिए तो नीचे टैप करें।", "मुझे कॉल करें",
                    "धन्यवाद। आपकी पसंद सहेज ली गई है।"),
            "kn", new Copy(
                    "ನಿಮ್ಮ ವಿವರಗಳನ್ನು ಉಳಿಸುವ ಮೊದಲು, ದಯವಿಟ್ಟು ನಮ್ಮ ಗೌಪ್ಯತಾ ಸೂಚನೆಯನ್ನು ಓದಿ: %s. ನಿಮ್ಮ ಆರೈಕೆಗಾಗಿ ಕ್ಲಿನಿಕ್ ನಿಮ್ಮ ಆರೋಗ್ಯ ಮಾಹಿತಿಯನ್ನು ಇಟ್ಟುಕೊಳ್ಳಲು ನೀವು ಒಪ್ಪುತ್ತೀರಾ?",
                    "ನಾನು ಒಪ್ಪುತ್ತೇನೆ", "ಬೇಡ",
                    "ಅಪಾಯಿಂಟ್‌ಮೆಂಟ್ ಅಪ್‌ಡೇಟ್‌ಗಳನ್ನು WhatsApp ನಲ್ಲಿ ಕಳುಹಿಸಬಹುದೇ? ಆಫರ್‌ಗಳು ಬೇರೆ; ನೀವು ಆಯ್ಕೆ ಮಾಡದ ಹೊರತು ಆಫ್ ಆಗಿರುತ್ತವೆ.",
                    "ಅಪ್‌ಡೇಟ್ ಮಾತ್ರ", "ಅಪ್‌ಡೇಟ್+ಆಫರ್", "ಅಪ್‌ಡೇಟ್ ಬೇಡ",
                    "ಪರವಾಗಿಲ್ಲ. ನಾವು ಏನನ್ನೂ ಉಳಿಸಿಲ್ಲ. ಕ್ಲಿನಿಕ್ ನಿಮಗೆ ಕರೆ ಮಾಡಬೇಕಾದರೆ ಕೆಳಗೆ ಒತ್ತಿ.", "ಕರೆ ಮಾಡಿ",
                    "ಧನ್ಯವಾದಗಳು. ನಿಮ್ಮ ಆಯ್ಕೆಗಳನ್ನು ಉಳಿಸಲಾಗಿದೆ."),
            "te", new Copy(
                    "మీ వివరాలను సేవ్ చేసే ముందు, దయచేసి మా గోప్యతా నోటీసు చదవండి: %s. మీ సంరక్షణ కోసం క్లినిక్ మీ ఆరోగ్య సమాచారాన్ని ఉంచుకోవడానికి మీరు అంగీకరిస్తారా?",
                    "అంగీకరిస్తున్నాను", "వద్దు",
                    "అపాయింట్‌మెంట్ అప్‌డేట్‌లను WhatsApp లో పంపవచ్చా? ఆఫర్లు వేరు; మీరు ఎంచుకుంటే తప్ప ఆఫ్‌లో ఉంటాయి.",
                    "అప్‌డేట్లు మాత్రమే", "అప్‌డేట్లు+ఆఫర్లు", "అప్‌డేట్లు వద్దు",
                    "పర్వాలేదు. మేము ఏమీ సేవ్ చేయలేదు. క్లినిక్ మీకు కాల్ చేయాలంటే క్రింద నొక్కండి.", "నాకు కాల్ చేయండి",
                    "ధన్యవాదాలు. మీ ఎంపికలు సేవ్ చేయబడ్డాయి."));

    private ConsentCopy() {
    }

    static boolean supported(String lang) {
        return BY_LANG.containsKey(lang);
    }

    static Copy of(String lang) {
        return BY_LANG.get(lang == null || !BY_LANG.containsKey(lang) ? DEFAULT : lang);
    }

    static Map<String, Copy> all() {
        return BY_LANG;
    }
}
