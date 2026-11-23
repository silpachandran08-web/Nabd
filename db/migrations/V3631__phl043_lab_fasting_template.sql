SET lock_timeout = '5s';
-- PHL-043: the fasting notice a patient gets when the doctor orders tests that need fasting (purpose lab_fasting; COM-012 catalogue,
-- UTILITY). Slots: first name and the number of hours the doctor set on the order. No test name and no medical wording (COM-014 lint),
-- no variable at the start or end of a body (Meta rule). The wording is a draft: NEEDS CLINICAL REVIEW (what "fasting" allows is
-- the clinic's instruction, not stated here). The hi, kn and te rows are MACHINE DRAFTS, unreviewed until a native-speaking clinician
-- signs them off in frontend/packages/i18n/catalogue/review.json. Meta approval is an external action, like the rest of the catalogue.
INSERT INTO template_catalogue (key, language, category, body, example_values, slot_types, purpose) VALUES
('lab_fasting','en','UTILITY','Hello {{1}}, your doctor has asked you to fast before your next visit. Please do not eat for {{2}} hours before you come. If you have questions, contact the clinic.',ARRAY['Asha','8']::text[],ARRAY['patient_first_name','count']::text[],'Tells the patient to come fasting for sample collection'),
('lab_fasting','hi','UTILITY','नमस्ते {{1}}, आपके डॉक्टर ने अगली बार आने से पहले खाली पेट रहने को कहा है। कृपया आने से पहले {{2}} घंटे तक कुछ न खाएं। कोई सवाल हो तो क्लिनिक से संपर्क करें।',ARRAY['Asha','8']::text[],ARRAY['patient_first_name','count']::text[],'Tells the patient to come fasting for sample collection'),
('lab_fasting','kn','UTILITY','ನಮಸ್ಕಾರ {{1}}, ಮುಂದಿನ ಭೇಟಿಗೆ ಮೊದಲು ಖಾಲಿ ಹೊಟ್ಟೆಯಲ್ಲಿ ಇರಲು ನಿಮ್ಮ ವೈದ್ಯರು ಹೇಳಿದ್ದಾರೆ. ದಯವಿಟ್ಟು ಬರುವ ಮೊದಲು {{2}} ಗಂಟೆಗಳ ಕಾಲ ಏನನ್ನೂ ತಿನ್ನಬೇಡಿ. ಪ್ರಶ್ನೆಗಳಿದ್ದರೆ ಕ್ಲಿನಿಕ್ ಅನ್ನು ಸಂಪರ್ಕಿಸಿ.',ARRAY['Asha','8']::text[],ARRAY['patient_first_name','count']::text[],'Tells the patient to come fasting for sample collection'),
('lab_fasting','te','UTILITY','నమస్కారం {{1}}, తదుపరి సందర్శనకు ముందు ఖాళీ కడుపుతో ఉండాలని మీ డాక్టర్ గారు చెప్పారు. దయచేసి వచ్చే ముందు {{2}} గంటల పాటు ఏమీ తినకండి. సందేహాలు ఉంటే క్లినిక్ ను సంప్రదించండి.',ARRAY['Asha','8']::text[],ARRAY['patient_first_name','count']::text[],'Tells the patient to come fasting for sample collection');
