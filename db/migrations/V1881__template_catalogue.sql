-- COM-012: platform-owned catalogue of utility templates (EN/HI/KN/TE; AR is added for Phase 4 by a new version).
-- Reference data like safety_dictionary: no tenant_id, no RLS, SELECT-only for nabd_app; content changes ship as a new
-- version row (a rejected entry is fixed once here for every tenant, COM-013 resubmits). Wording is generic utility text with
-- no clinical content. Translation review by native speakers and Meta approval are Needs external action.
SET lock_timeout = '5s';

CREATE TABLE template_catalogue (
  key            text NOT NULL,
  language       text NOT NULL,
  category       text NOT NULL CHECK (category IN ('UTILITY', 'AUTHENTICATION')),
  body           text NOT NULL,
  example_values text[] NOT NULL,
  slot_types     text[] NOT NULL,     -- one allow-listed type per {n} (COM-014 lint)
  buttons        jsonb,               -- quick replies / URL buttons; not yet sent to Meta
  purpose        text NOT NULL,
  version        int  NOT NULL DEFAULT 1,
  PRIMARY KEY (key, language, version),
  CHECK (cardinality(example_values) = cardinality(slot_types))
);
INSERT INTO template_catalogue (key, language, category, body, example_values, slot_types, purpose) VALUES
('appt_confirmed','en','UTILITY','Hello {{1}}, your appointment is confirmed for {{2}} at {{3}} with {{4}}. Thank you.',ARRAY['Asha','12 March','10:30','Dr Rao']::text[],ARRAY['patient_first_name','date','time','doctor_display_name']::text[],'Confirms a booked appointment'),
('appt_confirmed','hi','UTILITY','नमस्ते {{1}}, आपकी अपॉइंटमेंट {{2}} को {{3}} बजे {{4}} के साथ पक्की हो गई है। धन्यवाद।',ARRAY['Asha','12 March','10:30','Dr Rao']::text[],ARRAY['patient_first_name','date','time','doctor_display_name']::text[],'Confirms a booked appointment'),
('appt_confirmed','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, ನಿಮ್ಮ ಭೇಟಿ {{2}} ರಂದು {{3}} ಗಂಟೆಗೆ {{4}} ಅವರೊಂದಿಗೆ ದೃಢಪಟ್ಟಿದೆ. ಧನ್ಯವಾದಗಳು.',ARRAY['Asha','12 March','10:30','Dr Rao']::text[],ARRAY['patient_first_name','date','time','doctor_display_name']::text[],'Confirms a booked appointment'),
('appt_confirmed','te','UTILITY','నమస్కారం {{1}}, మీ సందర్శన {{2}} న {{3}} గంటలకు {{4}} గారితో ఖరారైంది. ధన్యవాదాలు.',ARRAY['Asha','12 March','10:30','Dr Rao']::text[],ARRAY['patient_first_name','date','time','doctor_display_name']::text[],'Confirms a booked appointment'),
('appt_reminder_24h','en','UTILITY','Hello {{1}}, this is a reminder of your appointment tomorrow, {{2}}, at {{3}}. Reply STOP to stop reminders.',ARRAY['Asha','12 March','10:30']::text[],ARRAY['patient_first_name','date','time']::text[],'Reminder one day before an appointment'),
('appt_reminder_24h','hi','UTILITY','नमस्ते {{1}}, आपको याद दिलाना है कि आपकी अपॉइंटमेंट कल {{2}} को {{3}} बजे है। संदेश बंद करने के लिए STOP लिखें।',ARRAY['Asha','12 March','10:30']::text[],ARRAY['patient_first_name','date','time']::text[],'Reminder one day before an appointment'),
('appt_reminder_24h','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, ನಾಳೆ {{2}} ರಂದು {{3}} ಗಂಟೆಗೆ ನಿಮ್ಮ ಭೇಟಿ ಇದೆ ಎಂದು ನೆನಪಿಸುತ್ತಿದ್ದೇವೆ. ಸಂದೇಶ ನಿಲ್ಲಿಸಲು STOP ಎಂದು ಕಳುಹಿಸಿ.',ARRAY['Asha','12 March','10:30']::text[],ARRAY['patient_first_name','date','time']::text[],'Reminder one day before an appointment'),
('appt_reminder_24h','te','UTILITY','నమస్కారం {{1}}, రేపు {{2}} న {{3}} గంటలకు మీ సందర్శన ఉందని గుర్తు చేస్తున్నాము. సందేశాలు ఆపడానికి STOP అని పంపండి.',ARRAY['Asha','12 March','10:30']::text[],ARRAY['patient_first_name','date','time']::text[],'Reminder one day before an appointment'),
('appt_reminder_2h','en','UTILITY','Hello {{1}}, your appointment is today at {{2}}. We look forward to seeing you.',ARRAY['Asha','10:30']::text[],ARRAY['patient_first_name','time']::text[],'Reminder two hours before an appointment'),
('appt_reminder_2h','hi','UTILITY','नमस्ते {{1}}, आपकी अपॉइंटमेंट आज {{2}} बजे है। हम आपका इंतज़ार करेंगे।',ARRAY['Asha','10:30']::text[],ARRAY['patient_first_name','time']::text[],'Reminder two hours before an appointment'),
('appt_reminder_2h','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, ನಿಮ್ಮ ಭೇಟಿ ಇಂದು {{2}} ಗಂಟೆಗೆ ಇದೆ. ನಿಮ್ಮನ್ನು ಎದುರು ನೋಡುತ್ತಿದ್ದೇವೆ.',ARRAY['Asha','10:30']::text[],ARRAY['patient_first_name','time']::text[],'Reminder two hours before an appointment'),
('appt_reminder_2h','te','UTILITY','నమస్కారం {{1}}, మీ సందర్శన ఈ రోజు {{2}} గంటలకు ఉంది. మీ కోసం ఎదురు చూస్తున్నాము.',ARRAY['Asha','10:30']::text[],ARRAY['patient_first_name','time']::text[],'Reminder two hours before an appointment'),
('appt_rescheduled','en','UTILITY','Hello {{1}}, your appointment has been moved to {{2}} at {{3}}. Please contact us if this time does not suit you.',ARRAY['Asha','12 March','10:30']::text[],ARRAY['patient_first_name','date','time']::text[],'Tells the patient the appointment time changed'),
('appt_rescheduled','hi','UTILITY','नमस्ते {{1}}, आपकी अपॉइंटमेंट अब {{2}} को {{3}} बजे कर दी गई है। यदि यह समय सुविधाजनक न हो तो कृपया हमसे संपर्क करें।',ARRAY['Asha','12 March','10:30']::text[],ARRAY['patient_first_name','date','time']::text[],'Tells the patient the appointment time changed'),
('appt_rescheduled','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, ನಿಮ್ಮ ಭೇಟಿಯನ್ನು {{2}} ರಂದು {{3}} ಗಂಟೆಗೆ ಬದಲಾಯಿಸಲಾಗಿದೆ. ಈ ಸಮಯ ಅನುಕೂಲವಾಗದಿದ್ದರೆ ದಯವಿಟ್ಟು ನಮ್ಮನ್ನು ಸಂಪರ್ಕಿಸಿ.',ARRAY['Asha','12 March','10:30']::text[],ARRAY['patient_first_name','date','time']::text[],'Tells the patient the appointment time changed'),
('appt_rescheduled','te','UTILITY','నమస్కారం {{1}}, మీ సందర్శనను {{2}} న {{3}} గంటలకు మార్చాము. ఈ సమయం సరిపోకపోతే దయచేసి మమ్మల్ని సంప్రదించండి.',ARRAY['Asha','12 March','10:30']::text[],ARRAY['patient_first_name','date','time']::text[],'Tells the patient the appointment time changed'),
('appt_cancelled_by_clinic','en','UTILITY','Hello {{1}}, we are sorry, we had to cancel your appointment on {{2}} at {{3}}. Please contact the clinic to book a new time.',ARRAY['Asha','12 March','10:30']::text[],ARRAY['patient_first_name','date','time']::text[],'Tells the patient the clinic cancelled the appointment'),
('appt_cancelled_by_clinic','hi','UTILITY','नमस्ते {{1}}, खेद है कि हमें {{2}} को {{3}} बजे की आपकी अपॉइंटमेंट रद्द करनी पड़ी। नया समय लेने के लिए कृपया क्लिनिक से संपर्क करें।',ARRAY['Asha','12 March','10:30']::text[],ARRAY['patient_first_name','date','time']::text[],'Tells the patient the clinic cancelled the appointment'),
('appt_cancelled_by_clinic','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, ಕ್ಷಮಿಸಿ, {{2}} ರಂದು {{3}} ಗಂಟೆಯ ನಿಮ್ಮ ಭೇಟಿಯನ್ನು ರದ್ದುಪಡಿಸಬೇಕಾಯಿತು. ಹೊಸ ಸಮಯಕ್ಕಾಗಿ ದಯವಿಟ್ಟು ಕ್ಲಿನಿಕ್ ಅನ್ನು ಸಂಪರ್ಕಿಸಿ.',ARRAY['Asha','12 March','10:30']::text[],ARRAY['patient_first_name','date','time']::text[],'Tells the patient the clinic cancelled the appointment'),
('appt_cancelled_by_clinic','te','UTILITY','నమస్కారం {{1}}, క్షమించండి, {{2}} న {{3}} గంటల మీ సందర్శనను రద్దు చేయవలసి వచ్చింది. కొత్త సమయం కోసం దయచేసి క్లినిక్ వారిని సంప్రదించండి.',ARRAY['Asha','12 March','10:30']::text[],ARRAY['patient_first_name','date','time']::text[],'Tells the patient the clinic cancelled the appointment'),
('doctor_delay','en','UTILITY','Hello {{1}}, {{2}} is running late. Your new expected time is {{3}}. Thank you for your patience.',ARRAY['Asha','Dr Rao','11:00']::text[],ARRAY['patient_first_name','doctor_display_name','time']::text[],'Tells the patient the doctor is running late'),
('doctor_delay','hi','UTILITY','नमस्ते {{1}}, {{2}} को आने में देर हो रही है। आपका नया अनुमानित समय {{3}} है। आपके धैर्य के लिए धन्यवाद।',ARRAY['Asha','Dr Rao','11:00']::text[],ARRAY['patient_first_name','doctor_display_name','time']::text[],'Tells the patient the doctor is running late'),
('doctor_delay','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, {{2}} ಅವರು ಬರಲು ತಡವಾಗುತ್ತಿದೆ. ನಿಮ್ಮ ಹೊಸ ಅಂದಾಜು ಸಮಯ {{3}}. ನಿಮ್ಮ ತಾಳ್ಮೆಗೆ ಧನ್ಯವಾದಗಳು.',ARRAY['Asha','Dr Rao','11:00']::text[],ARRAY['patient_first_name','doctor_display_name','time']::text[],'Tells the patient the doctor is running late'),
('doctor_delay','te','UTILITY','నమస్కారం {{1}}, {{2}} గారు రావడం ఆలస్యమవుతోంది. మీ కొత్త అంచనా సమయం {{3}}. మీ ఓపికకు ధన్యవాదాలు.',ARRAY['Asha','Dr Rao','11:00']::text[],ARRAY['patient_first_name','doctor_display_name','time']::text[],'Tells the patient the doctor is running late'),
('queue_your_turn','en','UTILITY','Hello {{1}}, your token number {{2}} is next. Please come to the reception.',ARRAY['Asha','A12']::text[],ARRAY['patient_first_name','token_number']::text[],'Tells the patient their token is next'),
('queue_your_turn','hi','UTILITY','नमस्ते {{1}}, आपका टोकन नंबर {{2}} अगला है। कृपया रिसेप्शन पर आइए।',ARRAY['Asha','A12']::text[],ARRAY['patient_first_name','token_number']::text[],'Tells the patient their token is next'),
('queue_your_turn','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, ನಿಮ್ಮ ಟೋಕನ್ ಸಂಖ್ಯೆ {{2}} ಮುಂದಿನದು. ದಯವಿಟ್ಟು ಸ್ವಾಗತ ಕೌಂಟರ್ ಬಳಿ ಬನ್ನಿ.',ARRAY['Asha','A12']::text[],ARRAY['patient_first_name','token_number']::text[],'Tells the patient their token is next'),
('queue_your_turn','te','UTILITY','నమస్కారం {{1}}, మీ టోకెన్ నంబర్ {{2}} తదుపరిది. దయచేసి రిసెప్షన్ వద్దకు రండి.',ARRAY['Asha','A12']::text[],ARRAY['patient_first_name','token_number']::text[],'Tells the patient their token is next'),
('leave_now','en','UTILITY','Hello {{1}}, it is almost your turn. Your token number is {{2}}. Please start for the clinic now.',ARRAY['Asha','A12']::text[],ARRAY['patient_first_name','token_number']::text[],'Asks the patient to start for the clinic'),
('leave_now','hi','UTILITY','नमस्ते {{1}}, आपकी बारी लगभग आ गई है। आपका टोकन नंबर {{2}} है। कृपया अब क्लिनिक के लिए निकलिए।',ARRAY['Asha','A12']::text[],ARRAY['patient_first_name','token_number']::text[],'Asks the patient to start for the clinic'),
('leave_now','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, ನಿಮ್ಮ ಸರದಿ ಸಮೀಪಿಸಿದೆ. ನಿಮ್ಮ ಟೋಕನ್ ಸಂಖ್ಯೆ {{2}}. ದಯವಿಟ್ಟು ಈಗ ಕ್ಲಿನಿಕ್ ಕಡೆಗೆ ಹೊರಡಿ.',ARRAY['Asha','A12']::text[],ARRAY['patient_first_name','token_number']::text[],'Asks the patient to start for the clinic'),
('leave_now','te','UTILITY','నమస్కారం {{1}}, మీ వంతు దాదాపు వచ్చింది. మీ టోకెన్ నంబర్ {{2}}. దయచేసి ఇప్పుడు క్లినిక్ వైపు బయలుదేరండి.',ARRAY['Asha','A12']::text[],ARRAY['patient_first_name','token_number']::text[],'Asks the patient to start for the clinic'),
('secure_link_ready','en','UTILITY','Hello {{1}}, a secure message from your clinic is ready. Open it here: {{2}} then follow the steps.',ARRAY['Asha','https://nabd.example/s/x1']::text[],ARRAY['patient_first_name','secure_link']::text[],'Points the patient to a secure page; nothing else is in the message'),
('secure_link_ready','hi','UTILITY','नमस्ते {{1}}, आपके क्लिनिक का एक सुरक्षित संदेश तैयार है। इसे यहाँ खोलिए: {{2}} और आगे के निर्देश देखिए।',ARRAY['Asha','https://nabd.example/s/x1']::text[],ARRAY['patient_first_name','secure_link']::text[],'Points the patient to a secure page; nothing else is in the message'),
('secure_link_ready','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, ನಿಮ್ಮ ಕ್ಲಿನಿಕ್ ಕಡೆಯಿಂದ ಒಂದು ಸುರಕ್ಷಿತ ಸಂದೇಶ ಸಿದ್ಧವಾಗಿದೆ. ಇಲ್ಲಿ ತೆರೆಯಿರಿ: {{2}} ನಂತರ ಮುಂದಿನ ಹಂತಗಳನ್ನು ಅನುಸರಿಸಿ.',ARRAY['Asha','https://nabd.example/s/x1']::text[],ARRAY['patient_first_name','secure_link']::text[],'Points the patient to a secure page; nothing else is in the message'),
('secure_link_ready','te','UTILITY','నమస్కారం {{1}}, మీ క్లినిక్ నుండి ఒక సురక్షిత సందేశం సిద్ధంగా ఉంది. ఇక్కడ తెరవండి: {{2}} తరువాత సూచనలను అనుసరించండి.',ARRAY['Asha','https://nabd.example/s/x1']::text[],ARRAY['patient_first_name','secure_link']::text[],'Points the patient to a secure page; nothing else is in the message'),
('recall_due','en','UTILITY','Hello {{1}}, it is time for your next visit. Please call us or reply to choose a time that suits you.',ARRAY['Asha']::text[],ARRAY['patient_first_name']::text[],'Invites the patient to book their next visit'),
('recall_due','hi','UTILITY','नमस्ते {{1}}, आपकी अगली मुलाकात का समय हो गया है। अपनी सुविधा का समय तय करने के लिए कृपया हमें कॉल करें या जवाब दें।',ARRAY['Asha']::text[],ARRAY['patient_first_name']::text[],'Invites the patient to book their next visit'),
('recall_due','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, ನಿಮ್ಮ ಮುಂದಿನ ಭೇಟಿಯ ಸಮಯ ಬಂದಿದೆ. ನಿಮಗೆ ಅನುಕೂಲವಾದ ಸಮಯ ನಿಗದಿಪಡಿಸಲು ದಯವಿಟ್ಟು ನಮಗೆ ಕರೆ ಮಾಡಿ ಅಥವಾ ಉತ್ತರಿಸಿ.',ARRAY['Asha']::text[],ARRAY['patient_first_name']::text[],'Invites the patient to book their next visit'),
('recall_due','te','UTILITY','నమస్కారం {{1}}, మీ తదుపరి సందర్శన సమయం వచ్చింది. మీకు వీలైన సమయం నిర్ణయించడానికి దయచేసి మాకు కాల్ చేయండి లేదా సమాధానం ఇవ్వండి.',ARRAY['Asha']::text[],ARRAY['patient_first_name']::text[],'Invites the patient to book their next visit'),
('review_request','en','UTILITY','Hello {{1}}, thank you for visiting us on {{2}}. We would value your feedback: {{3}} Thank you.',ARRAY['Asha','12 March','https://nabd.example/s/x1']::text[],ARRAY['patient_first_name','date','secure_link']::text[],'Asks for a review after a visit (Meta may recategorise)'),
('review_request','hi','UTILITY','नमस्ते {{1}}, {{2}} को हमारे यहाँ आने के लिए धन्यवाद। हमें आपकी राय जानकर खुशी होगी: {{3}} धन्यवाद।',ARRAY['Asha','12 March','https://nabd.example/s/x1']::text[],ARRAY['patient_first_name','date','secure_link']::text[],'Asks for a review after a visit (Meta may recategorise)'),
('review_request','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, {{2}} ರಂದು ನಮ್ಮಲ್ಲಿಗೆ ಬಂದಿದ್ದಕ್ಕಾಗಿ ಧನ್ಯವಾದಗಳು. ನಿಮ್ಮ ಅಭಿಪ್ರಾಯ ತಿಳಿಸಿ: {{3}} ಧನ್ಯವಾದಗಳು.',ARRAY['Asha','12 March','https://nabd.example/s/x1']::text[],ARRAY['patient_first_name','date','secure_link']::text[],'Asks for a review after a visit (Meta may recategorise)'),
('review_request','te','UTILITY','నమస్కారం {{1}}, {{2}} న మా వద్దకు వచ్చినందుకు ధన్యవాదాలు. మీ అభిప్రాయం తెలియజేయండి: {{3}} ధన్యవాదాలు.',ARRAY['Asha','12 March','https://nabd.example/s/x1']::text[],ARRAY['patient_first_name','date','secure_link']::text[],'Asks for a review after a visit (Meta may recategorise)'),
('feedback_request','en','UTILITY','Hello {{1}}, how was your visit with {{2}}? Please share a few words here: {{3}} Thank you.',ARRAY['Asha','Dr Rao','https://nabd.example/s/x1']::text[],ARRAY['patient_first_name','doctor_display_name','secure_link']::text[],'Asks how the visit went (Meta may recategorise)'),
('feedback_request','hi','UTILITY','नमस्ते {{1}}, {{2}} के साथ आपकी मुलाकात कैसी रही? कृपया यहाँ दो शब्द लिखें: {{3}} धन्यवाद।',ARRAY['Asha','Dr Rao','https://nabd.example/s/x1']::text[],ARRAY['patient_first_name','doctor_display_name','secure_link']::text[],'Asks how the visit went (Meta may recategorise)'),
('feedback_request','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, {{2}} ಅವರೊಂದಿಗಿನ ನಿಮ್ಮ ಭೇಟಿ ಹೇಗಿತ್ತು? ದಯವಿಟ್ಟು ಇಲ್ಲಿ ಎರಡು ಮಾತು ಬರೆಯಿರಿ: {{3}} ಧನ್ಯವಾದಗಳು.',ARRAY['Asha','Dr Rao','https://nabd.example/s/x1']::text[],ARRAY['patient_first_name','doctor_display_name','secure_link']::text[],'Asks how the visit went (Meta may recategorise)'),
('feedback_request','te','UTILITY','నమస్కారం {{1}}, {{2}} గారితో మీ సందర్శన ఎలా సాగింది? దయచేసి ఇక్కడ రెండు మాటలు రాయండి: {{3}} ధన్యవాదాలు.',ARRAY['Asha','Dr Rao','https://nabd.example/s/x1']::text[],ARRAY['patient_first_name','doctor_display_name','secure_link']::text[],'Asks how the visit went (Meta may recategorise)'),
('package_card','en','UTILITY','Hello {{1}}, your package card is ready. Open it here: {{2}} to see your visits. Thank you.',ARRAY['Asha','https://nabd.example/s/x1']::text[],ARRAY['patient_first_name','secure_link']::text[],'Points the patient to their package card'),
('package_card','hi','UTILITY','नमस्ते {{1}}, आपका पैकेज कार्ड तैयार है। अपनी मुलाकातें देखने के लिए इसे यहाँ खोलिए: {{2}} धन्यवाद।',ARRAY['Asha','https://nabd.example/s/x1']::text[],ARRAY['patient_first_name','secure_link']::text[],'Points the patient to their package card'),
('package_card','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, ನಿಮ್ಮ ಪ್ಯಾಕೇಜ್ ಕಾರ್ಡ್ ಸಿದ್ಧವಾಗಿದೆ. ನಿಮ್ಮ ಭೇಟಿಗಳನ್ನು ನೋಡಲು ಇಲ್ಲಿ ತೆರೆಯಿರಿ: {{2}} ಧನ್ಯವಾದಗಳು.',ARRAY['Asha','https://nabd.example/s/x1']::text[],ARRAY['patient_first_name','secure_link']::text[],'Points the patient to their package card'),
('package_card','te','UTILITY','నమస్కారం {{1}}, మీ ప్యాకేజీ కార్డ్ సిద్ధంగా ఉంది. మీ సందర్శనలను చూడటానికి ఇక్కడ తెరవండి: {{2}} ధన్యవాదాలు.',ARRAY['Asha','https://nabd.example/s/x1']::text[],ARRAY['patient_first_name','secure_link']::text[],'Points the patient to their package card'),
('package_expiry','en','UTILITY','Hello {{1}}, your package expires on {{2}}. Please contact the clinic to use your remaining visits.',ARRAY['Asha','30 April']::text[],ARRAY['patient_first_name','date']::text[],'Tells the patient a package is about to expire'),
('package_expiry','hi','UTILITY','नमस्ते {{1}}, आपका पैकेज {{2}} को समाप्त हो रहा है। बची हुई मुलाकातों के लिए कृपया क्लिनिक से संपर्क करें।',ARRAY['Asha','30 April']::text[],ARRAY['patient_first_name','date']::text[],'Tells the patient a package is about to expire'),
('package_expiry','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, ನಿಮ್ಮ ಪ್ಯಾಕೇಜ್ {{2}} ರಂದು ಮುಕ್ತಾಯವಾಗುತ್ತದೆ. ಉಳಿದ ಭೇಟಿಗಳಿಗಾಗಿ ದಯವಿಟ್ಟು ಕ್ಲಿನಿಕ್ ಅನ್ನು ಸಂಪರ್ಕಿಸಿ.',ARRAY['Asha','30 April']::text[],ARRAY['patient_first_name','date']::text[],'Tells the patient a package is about to expire'),
('package_expiry','te','UTILITY','నమస్కారం {{1}}, మీ ప్యాకేజీ {{2}} న ముగుస్తుంది. మిగిలిన సందర్శనల కోసం దయచేసి క్లినిక్ వారిని సంప్రదించండి.',ARRAY['Asha','30 April']::text[],ARRAY['patient_first_name','date']::text[],'Tells the patient a package is about to expire'),
('optout_confirm','en','UTILITY','Hello {{1}}, you have stopped receiving messages from us. Reply START to receive them again.',ARRAY['Asha']::text[],ARRAY['patient_first_name']::text[],'Confirms the patient stopped messages'),
('optout_confirm','hi','UTILITY','नमस्ते {{1}}, आपने हमारे संदेश लेना बंद कर दिया है। दोबारा पाने के लिए START लिखें।',ARRAY['Asha']::text[],ARRAY['patient_first_name']::text[],'Confirms the patient stopped messages'),
('optout_confirm','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, ನೀವು ನಮ್ಮ ಸಂದೇಶಗಳನ್ನು ಪಡೆಯುವುದನ್ನು ನಿಲ್ಲಿಸಿದ್ದೀರಿ. ಮತ್ತೆ ಪಡೆಯಲು START ಎಂದು ಕಳುಹಿಸಿ.',ARRAY['Asha']::text[],ARRAY['patient_first_name']::text[],'Confirms the patient stopped messages'),
('optout_confirm','te','UTILITY','నమస్కారం {{1}}, మీరు మా సందేశాలు అందుకోవడం ఆపేశారు. మళ్లీ అందుకోవడానికి START అని పంపండి.',ARRAY['Asha']::text[],ARRAY['patient_first_name']::text[],'Confirms the patient stopped messages'),
('otp_login','en','AUTHENTICATION','Your verification code is {{1}}. Do not share it with anyone.',ARRAY['123456']::text[],ARRAY['otp_code']::text[],'One-time sign-in code'),
('otp_login','hi','AUTHENTICATION','आपका सत्यापन कोड {{1}} है। इसे किसी के साथ साझा न करें।',ARRAY['123456']::text[],ARRAY['otp_code']::text[],'One-time sign-in code'),
('otp_login','kn','AUTHENTICATION','ನಿಮ್ಮ ಪರಿಶೀಲನಾ ಕೋಡ್ {{1}}. ಇದನ್ನು ಯಾರೊಂದಿಗೂ ಹಂಚಿಕೊಳ್ಳಬೇಡಿ.',ARRAY['123456']::text[],ARRAY['otp_code']::text[],'One-time sign-in code'),
('otp_login','te','AUTHENTICATION','మీ ధృవీకరణ కోడ్ {{1}}. దీనిని ఎవరితోనూ పంచుకోకండి.',ARRAY['123456']::text[],ARRAY['otp_code']::text[],'One-time sign-in code');
INSERT INTO template_lint_rules (language, kind, pattern, source) VALUES ('*','allow_slot','otp_code','COM-012 v1')
  ON CONFLICT DO NOTHING;

-- Catalogue-derived tenant rows: queued until the background submitter (CatalogueSubmissionJob) hands them to Meta.
ALTER TABLE whatsapp_templates ADD COLUMN catalogue_key text, ADD COLUMN catalogue_version int, ADD COLUMN buttons jsonb,
  ADD COLUMN purpose text, ADD COLUMN submit_attempts int NOT NULL DEFAULT 0, ADD COLUMN next_attempt_at timestamptz;
ALTER TABLE whatsapp_templates DROP CONSTRAINT whatsapp_templates_category_check;
ALTER TABLE whatsapp_templates ADD CONSTRAINT whatsapp_templates_category_check
  CHECK (category IN ('UTILITY', 'MARKETING', 'AUTHENTICATION')) NOT VALID;
ALTER TABLE whatsapp_templates VALIDATE CONSTRAINT whatsapp_templates_category_check;
ALTER TABLE whatsapp_templates DROP CONSTRAINT whatsapp_templates_status_check;
ALTER TABLE whatsapp_templates ADD CONSTRAINT whatsapp_templates_status_check
  CHECK (status IN ('queued', 'pending', 'approved', 'flagged', 'paused', 'disabled', 'rejected')) NOT VALID;
ALTER TABLE whatsapp_templates VALIDATE CONSTRAINT whatsapp_templates_status_check;
CREATE UNIQUE INDEX whatsapp_templates_catalogue_once ON whatsapp_templates (tenant_id, catalogue_key, language) WHERE catalogue_key IS NOT NULL;

-- The submitter works across tenants: which tenants have templates due (same shape as the other cross-tenant helpers).
CREATE FUNCTION due_catalogue_template_tenants(p_limit int) RETURNS SETOF uuid
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
  SELECT DISTINCT tenant_id FROM whatsapp_templates
   WHERE status = 'queued' AND (next_attempt_at IS NULL OR next_attempt_at <= now()) LIMIT p_limit;
$$;
REVOKE ALL ON FUNCTION due_catalogue_template_tenants(int) FROM PUBLIC;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON template_catalogue FROM nabd_app;
    GRANT SELECT ON template_catalogue TO nabd_app;
    GRANT EXECUTE ON FUNCTION due_catalogue_template_tenants(int) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
