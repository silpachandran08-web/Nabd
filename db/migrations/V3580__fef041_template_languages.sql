SET lock_timeout = '5s';
-- FEF-041: every patient template in en, hi, kn and te. Fills the gaps the catalogue check found: appt_nudge, package_session_nudge and
-- package_grace had no kn/te, queue_paused had no hi/kn/te. These nine rows are MACHINE DRAFTS: unreviewed until a native-speaking
-- clinician signs them off in frontend/packages/i18n/catalogue/review.json (external; see scripts/ci/patient_catalogue.py). Utility wording,
-- no clinical content, no PHI, no variable at the start or end of a body (Meta rule). Existing tenants keep the templates they already
-- instantiated; a new language reaches a clinic only through a new version or a new instantiation.
INSERT INTO template_catalogue (key, language, category, body, example_values, slot_types, purpose) VALUES
('appt_nudge','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, {{2}} ರಂದು {{3}} ಗಂಟೆಗೆ ಇರುವ ನಿಮ್ಮ ಭೇಟಿಯ ಬಗ್ಗೆ ನಿಮ್ಮಿಂದ ಉತ್ತರ ಬಂದಿಲ್ಲ. ದೃಢೀಕರಿಸಲು ದಯವಿಟ್ಟು ಉತ್ತರಿಸಿ, ಅಥವಾ ಬದಲಾಯಿಸಲು ನಮ್ಮನ್ನು ಸಂಪರ್ಕಿಸಿ.',ARRAY['Asha','12 March','10:30']::text[],ARRAY['patient_first_name','date','time']::text[],'Asks for a confirmation the patient has not given yet'),
('appt_nudge','te','UTILITY','నమస్కారం {{1}}, {{2}} న {{3}} గంటలకు ఉన్న మీ సందర్శన గురించి మీ నుండి మాకు సమాధానం రాలేదు. నిర్ధారించడానికి దయచేసి సమాధానం ఇవ్వండి, లేదా మార్చడానికి మమ్మల్ని సంప్రదించండి.',ARRAY['Asha','12 March','10:30']::text[],ARRAY['patient_first_name','date','time']::text[],'Asks for a confirmation the patient has not given yet'),
('package_session_nudge','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, ನಿಮ್ಮ ಮುಂದಿನ ಪ್ಯಾಕೇಜ್ ಸೆಶನ್ ಸಮಯ ಬಂದಿದೆ. ಸಮಯ ಕಾಯ್ದಿರಿಸಲು ಈ ಸಂದೇಶಕ್ಕೆ ಉತ್ತರಿಸಿ.',ARRAY['Asha']::text[],ARRAY['patient_first_name']::text[],'Reminds the patient to book the next package session'),
('package_session_nudge','te','UTILITY','నమస్కారం {{1}}, మీ తదుపరి ప్యాకేజీ సెషన్ సమయం వచ్చింది. సమయం బుక్ చేసుకోవడానికి ఈ సందేశానికి సమాధానం ఇవ్వండి.',ARRAY['Asha']::text[],ARRAY['patient_first_name']::text[],'Reminds the patient to book the next package session'),
('package_grace','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, ನಿಮ್ಮ ಪ್ಯಾಕೇಜ್ ಮಾನ್ಯತೆ ಮುಗಿದಿದೆ. {{2}} ರವರೆಗೆ ಉಳಿದ ಸೆಶನ್ಗಳನ್ನು ನೀವು ಇನ್ನೂ ಬಳಸಬಹುದು.',ARRAY['Asha','19 March']::text[],ARRAY['patient_first_name','date']::text[],'Tells the patient the package is in its grace period'),
('package_grace','te','UTILITY','నమస్కారం {{1}}, మీ ప్యాకేజీ చెల్లుబాటు ముగిసింది. {{2}} వరకు మిగిలిన సెషన్లను మీరు ఇంకా ఉపయోగించుకోవచ్చు.',ARRAY['Asha','19 March']::text[],ARRAY['patient_first_name','date']::text[],'Tells the patient the package is in its grace period'),
('queue_paused','hi','UTILITY','डॉक्टर कुछ देर के लिए उपलब्ध नहीं हैं; हम आपको जानकारी देंगे।',ARRAY[]::text[],ARRAY[]::text[],'Tells waiting patients the queue is paused, without a reason'),
('queue_paused','kn','UTILITY','ವೈದ್ಯರು ಸ್ವಲ್ಪ ಸಮಯ ಲಭ್ಯವಿಲ್ಲ; ನಾವು ನಿಮಗೆ ತಿಳಿಸುತ್ತೇವೆ.',ARRAY[]::text[],ARRAY[]::text[],'Tells waiting patients the queue is paused, without a reason'),
('queue_paused','te','UTILITY','డాక్టర్ గారు కొద్దిసేపు అందుబాటులో లేరు; మేము మీకు తెలియజేస్తాము.',ARRAY[]::text[],ARRAY[]::text[],'Tells waiting patients the queue is paused, without a reason');
