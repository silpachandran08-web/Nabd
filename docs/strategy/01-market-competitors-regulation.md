# Nabd: Market, Competitors and Regulation (India + GCC)

**Owner:** Market Research & Regulatory Analyst
**As of:** 2026-10-17
**Status:** v1. A desk-research baseline. It is not legal advice. Every regulatory item flagged "verify" needs sign-off from local counsel before we build against it.

**How to read this document**
- Sources are cited inline with URLs. Where the only source is a vendor blog, BSP (WhatsApp Business Solution Provider) page or software directory, it is labelled *(secondary)*.
- **(unverified)** means I could not confirm the claim from a primary or reputable secondary source during this research. **(estimate)** means it is my own modelling or inference.
- Several regimes are in the middle of changing as of October 2026. These are marked **⚠ in transition**: India's DPDP Rules, the UAE PDPL Executive Regulations, UAE e-invoicing, KSA ZATCA Wave 25, Meta WhatsApp pricing, and DHIS/ABDM.

---

## Executive summary

1. **Launch in India first, in Bengaluru and Hyderabad. The beachhead is solo-doctor and small single-specialty clinics** (paediatrics, gynaecology, ENT, dermatology, orthopaedics, GP) that run walk-in token queues. India offers the strongest "queue in the sun" pain, the cheapest WhatsApp unit economics (about ₹0.115 per utility message), and the lightest licensing barrier before revenue. Government-subsidised interoperability rails (ABDM) are also already in place.
2. **The second market is the UAE (Dubai first), targeted around months 9 to 15. KSA comes third, around month 18 or later.** The GCC brings 5 to 10x the ARPU, but it also brings gating integrations. These are NABIDH (Dubai), Malaffi (Abu Dhabi) and Riayati (northern emirates) health information exchanges, DHPO and Shafafiya e-claims, and in KSA NPHIES, ZATCA Phase 2 e-invoicing and in-Kingdom hosting. Qatar is small and dominated by the public sector (HMC/PHCC). Treat it as opportunistic.
3. **White space Nabd can own:** (a) **live, consult-duration-aware queue ETA** delivered on WhatsApp, which almost no incumbent does well; (b) **a single product that scales from a solo doctor to a hospital chain**, where incumbents split into cheap clinic tools and heavy HMS; (c) **consented, time-boxed cross-facility record sharing**, built natively on ABDM's HIE-CM consent artefacts in India and on the HIEs in the GCC; (d) **a doctor identity that follows the doctor across facilities** while records stay with each facility.
4. **The competition is crowded but fragmented.** Practo (Ray/Insta) is the incumbent across India and the GCC. Eka Care and HealthPlix lead on EMR and AI. Clinicea, Unite and others hold Dubai. Epic and Oracle Health hold the high end. WhatsApp-first queue start-ups already exist (e.g. TOKENN in Chennai, at about ₹1 per patient), so **"WhatsApp booking" alone is not a moat.** ETA accuracy, workflow depth and interop are.
5. **India regulation is manageable but tightening.** DPDP Rules 2025 were notified on 13 Nov 2025. Consent-manager rules apply from **12 Nov 2026** and most obligations from **12–13 May 2027**: 72-hour breach reporting, one-year log retention, 48-hour pre-erasure notice and a ₹250 crore penalty cap. ABDM M1/M2/M3 certification is voluntary, but it is a buying criterion for hospitals and a prerequisite for NABH HIS/EMR certification (which requires M3).
6. **The UAE is the strictest market on residency.** Federal Law No. 2 of 2019 requires health data to be stored and processed in the UAE, with 10 narrow exception categories under Resolution 51/2021. ADHICS 2.0 requires full localisation in Abu Dhabi. Records must be kept **for at least 25 years**. The PDPL Executive Regulations are still unpublished, with a widely cited compliance date of 1 Jan 2027 **⚠**.
7. **In KSA, compliance is the product.** The PDPL has been enforceable since 14 Sep 2024, with 72-hour breach notice to SDAIA. Transfer rules were amended in Sep 2024 (SCCs, BCRs, certification). NCA CCC-2:2024 covers localisation. NPHIES (FHIR, ICD-10-AM/ACHI/SBS) is needed for insured patients. ZATCA Phase 2 Wave 25 brings **every VAT-registered taxpayer above SAR 187,500 into scope by 1 Feb 2027**.
8. **WhatsApp economics:** Meta moved to per-message pricing on 1 Jul 2025. Patient-initiated service replies and utility templates sent inside the 24-hour window are free under Meta's published rules. A booking-plus-ETA flow therefore costs roughly ₹0.2–0.5 per visit in India and USD 0.01–0.03 in the GCC *(estimate)*. Since Jan 2026 Meta bans *general-purpose* AI chatbots on the Business API. A booking assistant specific to the business is allowed.
9. **Pricing hypothesis:** India: free solo tier (queue plus WhatsApp booking, capped), Pro at **₹999–1,499 per doctor per month**, and multi-doctor or hospital tiers per module. UAE: **AED 199–349 per practitioner per month**. KSA: **SAR 249–399 per practitioner per month**, plus NPHIES/ZATCA add-ons. Anchors: Practo Ray at ₹999–1,999/month, Eka at about ₹17k–19k/year per doctor, Clinicea at AED 199 per practitioner per month.
10. **Top risks:** AI CDS being classified as Software as a Medical Device (CDSCO draft guidance of Oct 2025; SFDA MDS-G010); a GCC certification treadmill (NABIDH, Malaffi, NPHIES, ZATCA) that slows expansion; Meta pricing and policy changes; low willingness to pay in India and pharma-subsidised "free" EMRs; and residency architecture (one region per tenant) that has to be right from day one.

---

## 1. Market sizing and dynamics

### 1.1 India

| Metric | Value | Source / confidence |
|---|---|---|
| Registered allopathic doctors | ~13.86–13.88 lakh (1.39M); plus 7.5 lakh AYUSH; doctor:population 1:811 (assumes 80% availability) | Rajya Sabha reply, Dec 2025, via [Medical Dialogues](https://medicaldialogues.in/news/health/doctors/indias-doctor-population-ratio-1811-govt-tells-parliament-159970); Feb 2025 RS answer [PQ 267](https://rsdebate.nic.in/bitstream/123456789/757882/1/PQ_267_04022025_U267_p410_p410.pdf) |
| Health facilities on ABDM HFR | >5.47 lakh (Aug 2026); 10.5 lakh professionals on HPR; 96.43 crore ABHAs; >110 crore records linked | [Indian Pharma Post](https://www.indianpharmapost.com/policy/ayushman-bharat-digital-mission-crosses-9395-crore-abha-ids-20877), [GKToday](https://www.gktoday.in/ayushman-bharat-digital-mission-crosses-93-95-crore-health-accounts/) *(secondary reporting of MoHFW figures)* |
| Facilities on ABDM-enabled software | 2.72 lakh (Jul 2026 MSG meeting) | [Digital Health News](https://www.digitalhealthnews.com/india-s-state-wise-digital-health-progress-in-2026-from-scale-to-utilization-interoperability-and-connected-care) *(secondary)* |
| Small private hospitals | ~24,000 private hospitals/nursing homes with fewer than 50 beds (about half of private hospitals) | [IQVIA case study, 2023](https://www.iqvia.com/locations/india/library/case-studies/predictive-typing-tool-for-segmenting-hospital-accounts-in-india) *(industry estimate)* |
| Private share of hospital care | 55% of population used private hospitals (61% urban); 42% public | NSS 75th round 2017–18, via [India Environment Portal](https://indiaenvironmentportal.org.in/reports-and-documents/health-in-india-july-2017-june-2018) |
| Out-of-pocket expenditure | 39.4% of total health expenditure (2021–22), down from 62.6% (2014–15) | NHA 2020–21/2021–22, [PIB doc](https://static.pib.gov.in/WriteReadData/specificdocs/documents/2024/oct/doc2024104408201.pdf); figure via secondary summaries |
| Clinics still on paper tokens | "76% of 1.5M clinics" | Hackathon doc *(unverified; do not quote externally)* |
| HIS market | ~USD 4.08B (2024) to ~USD 12.9B (2030), 21.3% CAGR | [Ken Research](https://www.kenresearch.com/articles/clinical-digitisation-barrier-hms-vendors-india) *(estimate, vendor research)* |

**Payer landscape.** OPD care is overwhelmingly cash/UPI. Insurers settled a record 32.6M health claims (₹94,248 crore) in FY25. Cashless claims made up about 66% of claim value ([India Med Today](https://indiamedtoday.com/record-health-insurance-claims-settled-in-fy25-average-payout-per-claim-declines/), *secondary*). IRDAI is pushing 100% cashless processing, with pre-authorisation within 1 hour and discharge approval within 3 hours ([Outlook Money](https://outlookmoney.com/insurance/irdai-to-introduce-100-cashless-insurance-settlement-what-policyholders-need-to-know)). Insurance is still mostly IPD. TPAs (e.g. Vidal Health, now owned by Bajaj Finserv Health, [Digital Health News](https://www.digitalhealthnews.com/bajaj-finserv-arm-acquires-vidal-healthcare-services-in-a-deal-worth-inr-325-cr)) matter for hospitals, not solo clinics. NHCX is the FHIR claims exchange: 34 insurers and TPAs were live by Jul 2024 ([MoHFW](https://mohfw.gov.in/press-info/7558)). It is relevant once Nabd reaches hospitals. Individual health insurance premiums became GST-exempt on 22 Sep 2025 ([Business Standard](https://www.business-standard.com/economy/news/gst-reforms-new-rates-slab-reduction-tax-cuts-exemptions-september-22-125092200171_1.html)).

**WhatsApp.** India is WhatsApp's largest market. Estimates range from 490M to 650M users ([Infobip](https://www.infobip.com/blog/whatsapp-statistics-2025-global-usage-market-overview), [GrabOn](https://www.grabon.in/indulge/tech/whatsapp-statistics/), *secondary; no official Meta country figure*). For practical purposes, effectively every urban patient and doctor uses it.

**Typical clinic profiles and buyer personas (estimates based on market observation)**

| Segment | Size | Buyer | Buying process | Current tools |
|---|---|---|---|---|
| Solo GP or specialist | 1 doctor, 0–1 assistant, 30–120 OPD/day; walk-in token queue | Doctor-owner | Same-day decision. A demo from a peer or medical rep. Price sensitive below ₹1,500/month. Wants free trials | Paper register, physical tokens, WhatsApp chats, sometimes Practo Ray, Eka or HealthPlix |
| Single-specialty clinic | 2–5 doctors, receptionist, maybe a pharmacy or lab counter | Senior doctor or partner | 1–4 weeks. Asks for references | Ray, Eka, DocOn, Drlogy, MocDoc, Marg for pharmacy |
| Multi-specialty clinic or nursing home | 5–30 doctors, 10–50 beds | Owner plus admin/IT manager | 1–3 months. Wants a module checklist and NABH readiness | MocDoc, KareXpert, Ezovion, local HMS vendors |
| Corporate chain | 100+ beds, multi-site | CIO, CFO, procurement | RFP, 6–18 months. Wants ABDM M1–M3, NABH DHS and NHCX | Insta, KareXpert, in-house systems, Oracle/Epic at the very top |

**Price points (India).** Practo Ray: ₹999–1,999/month per clinic ([FitGap](https://us.fitgap.com/products/ray-by-practo), *secondary*). Eka Care: ₹16,999–18,749/year for 1 doctor + 2 staff; Eka Clinic Pro ₹1,00,000/year for 5 doctors ([SoftwareFinder](https://softwarefinder.com/emr-software/eka-care), *secondary*). Doctors App: ₹8,999–14,999/year ([doctorsapp.in](https://www.doctorsapp.in/emr/cheapest-clinic-management-system-software-india-2024)). Drlogy from ₹299/month ([GetApp](https://www.getapp.co.uk/software/2072671/drlogy), *secondary*). The general clinic range is ₹10k–40k/year ([Codingclave](https://codingclave.com/guides/clinic-management-software-cost-india-2026), *secondary*). Pharmacy: Marg ERP ₹8,100 one-time (Basic) plus about ₹3,500/year AMC, or Marg Cloud at about ₹999/user/month ([itforsme](https://itforsme.in/pricing/marg-erp-india), [SaaSRat](https://saasrat.com/products/marg-pharmaceutical-erp), *secondary*). **Implication:** willingness to pay per doctor tops out around ₹1,000–1,500/month unless the product saves visible staff or patient time.

**Dynamics to exploit**
- **Government has already validated digital queueing.** ABDM "Scan & Share" OPD registration had generated 6.64 crore tokens at 17,481 facilities by Nov 2024 (about 2 lakh/day). It claims queue time fell from 30–40 minutes to 5–10 minutes ([MoHFW](https://www.mohfw.gov.in/press-info/7908)). Any ABDM-compliant HMIS registered on HFR can generate the QR. Nabd should support Scan & Share natively, so patients can use either their ABHA or a WhatsApp path.
- **DHIS incentives.** ₹20 per additional digitised record above 100/month, capped at ₹4 crore per facility or per digital solution company. The scheme was reportedly extended to 31 December **⚠** (year unclear; [Sarkaritel](https://www.sarkaritel.com/?p=337238), *secondary, verify with NHA*).

### 1.2 UAE

| Metric | Value | Source |
|---|---|---|
| Dubai licensed facilities | ~5,800 (2025) vs 5,340 (2024), +8%. Includes 55 hospitals plus specialised, general and dental clinics, day-surgery and home-care centres (the category figures given cover only part of the total) | [Dubai Media Office, Feb 2026](https://mediaoffice.ae/en/news/2026/february/18-02/dubais-healthcare-ecosystem-posts-record-growth-in-2025) |
| Dubai private healthcare workforce | >69,400 (2025) vs 64,100 (2024) | same |
| NABIDH | 91 EMR systems connected, covering 1,888 licensed facilities (mid-2025) | [Pabau](https://pabau.com/blog/emr-software-dubai/) *(secondary, citing DHA)* |
| Riayati (MOHAP, federal) | >3,000 facilities connected | [MOHAP](https://mohap.gov.ae/en/riayati/about-numr) |
| Malaffi (Abu Dhabi) | 95% of hospitals with EMRs connected (2021); DoH Circular 2026/10 makes minimum-dataset onboarding mandatory for **all** facilities | [Hospitals Magazine](https://hospitalsmagazine.com/?p=10873), [DoH circular](https://www.doh.gov.ae/-/media/84A213F71AA545D69E01A23A0A99C598.ashx) |
| WhatsApp penetration | 80–90%+ | [Infobip](https://www.infobip.com/blog/whatsapp-statistics-2025-global-usage-market-overview) *(secondary)* |

**Payer landscape.** The UAE is insurance-led. Mandatory employer-funded health insurance now covers private-sector and domestic workers in **all emirates** from 1 Jan 2025 (previously only Abu Dhabi and Dubai). The basic plan starts at AED 320/year with a 25% outpatient co-pay capped at AED 100 ([Gulf News](https://gulfnews.com/living-in-uae/health/uaes-mandatory-health-insurance-for-workers-starts-today-everything-you-need-to-know-1.500009608), [ATB Legal](https://atblegal.com/blog/emirates-mandatory-health-insurance-for-private-and-domestic/)). As a result, **every clinic needs e-claims**: DHPO/eClaimLink in Dubai ([eClaimLink](https://eclaimlink.ae/about.aspx)) and Shafafiya in Abu Dhabi ([DoH](https://www.doh.gov.ae/en/Shafafiya/reporting)). Most also need TPA workflows (NAS, Nextcare, MedNet and others; *unverified list*) and prior authorisation.

**Buyers.** In Dubai and Sharjah, many polyclinics are owned or run by South Asian expatriates and serve blue-collar insured populations. They are price-conscious but pay in AED. Practo says the UAE and KSA make up 10–15% of its revenue, and its consumer push in the UAE targets the Indian diaspora ([Angel One](https://www.angelone.in/news/market-updates/practo-takes-healthcare-platform-to-uae-targets-indian-diaspora), [Enterprise](https://enterpriseam.com/mena-india/2026/02/11/why-uae-markets-remain-the-epicenter-of-practos-global-consumer-expansion/)). Premium groups (Aster, Mediclinic, NMC, Burjeel) buy through RFPs. **Price anchor:** Clinicea at AED 199 per practitioner per month, all-in ([Clinicea](https://clinicea.com/emr-management-software-dubai)). Capterra lists USD 59–89 per user per month ([Capterra](https://www.capterra.com/p/131430/Clinicea/pricing/)).

### 1.3 Saudi Arabia (KSA)

| Metric | Value | Source |
|---|---|---|
| Private-sector physicians | 38,977 (2024), of whom 4,314 are Saudi | MOH Statistical Yearbook via [GRC Gulf Migration](https://gulfmigration.grc.net/?p=11317) |
| Medical complexes and centres (all sectors) | >5,888; >20,756 licences issued (Jun 2023) | [MOH](https://www.moh.gov.sa/en/Ministry/MediaCenter/News/Pages/News-2023-06-04-001.aspx) |
| Private health insurance | ~13–13.2M insured lives (2024) | [Argaam/CHI](https://www.argaam.com/en/article/articledetail/id/1781050), [Insurance Business (Bupa review)](https://www.insurancebusinessmag.com/asia/news/life-insurance/saudi-arabia-forecasts-insurance-boom-by-decades-end-548463.aspx) |
| Sehhaty (MOH app) | >31M users (2025) | [Al Riyadh Daily](https://alriyadhdaily.com/article/ffb46c2aa8c040e39f225fc6e3e4d910) |
| Vision 2030 target | Private sector contribution up from 40% to 65%; privatise 290 hospitals and 2,300 PHCs | [US Trade.gov](https://www.trade.gov/country-commercial-guides/saudi-arabia-healthcare) *(reported target)* |
| WhatsApp | >75% active use in the region | Northwestern study via [Timelines](https://timelines.ai/whatsapp-stats) *(secondary)* |

**Dynamics.** Public delivery is being corporatised into roughly 20 health clusters under the Health Holding Company ([Healthcare IT News](https://www.healthcareitnews.com/news/emea/saudi-ministry-health-announces-role-new-health-holding-company), [Tamimi](https://tamimi.com/law-update-articles/saudi-arabia-healthcare-transformation-a-2021-lookback)). The private market includes "hundreds of fragmented polyclinics" ([Arab News](https://www.arabnews.com/node/1347176)) plus large groups (Dr. Sulaiman Al Habib, Mouwasat, Saudi German). Sehhaty dominates the public patient front door. Private clinics depend on WhatsApp and private apps. The **payer side runs on NPHIES** (CHI). Insured expatriates and their dependants are the core private OPD volume. Saudi nationals are increasingly insured too. **Price points:** none were public. Clinicea charges about USD 60/month for ZATCA integration alone ([Clinicea help](https://help.clinicea.com/en/article/zakat-integration-pricing-setup-and-implementation-overview-g5rsok/)). Saudi-native players such as Clinicy market Arabic-first booking with WhatsApp ([Yespress](https://yespress.io/clinicy)). SAR 250–450 per practitioner per month is my *(estimate)*.

### 1.4 Qatar

The system is dominated by the public sector: Hamad Medical Corporation (Oracle Cerner) and PHCC. As of 2019 there were **6 private hospitals and more than 200 private polyclinics** ([Oxford Business Group](https://oxfordbusinessgroup.com/reports/qatar/2020-report/economy/state-of-care-health-facilities-grow-with-mix-of-private-and-public-projects); no newer official count found). Law No. 22 of 2021 makes employer-provided insurance mandatory for expatriates. Visitor insurance has been mandatory since Feb 2023 ([OBG](https://oxfordbusinessgroup.com/universal-coverage-new-regulations-mandate-employer-sponsored-private-health-insurance-for-expatriates-and-foreign-tourists), [MOPH visitor guide](https://www.moph.gov.qa/english/departments/ministeroffice/hfid/Documents/Visitor-Guide-to-Medical-Care-in-Qatar-English.pdf)). This growing insured-expat base expands private OPD. The market is small: perhaps 300–500 private facilities in total *(estimate)*. Pricing would be similar to the UAE.

### 1.5 Optional markets (short notes)

| Market | Notes |
|---|---|
| **Oman** | PDPL (Royal Decree 6/2022). Processing health data reportedly needs a permit from the competent authority ([HFW](https://www.hfw.com/insights/personal-data-in-the-gcc/)). VAT 5% *(background knowledge, not re-verified)*. Small market. WhatsApp utility about USD 0.025/message, which is expensive *(secondary)*. |
| **Kuwait** | No comprehensive PDP law. CITRA's Data Privacy Protection Regulation covers telecoms and ICT providers ([HFW](https://www.hfw.com/insights/personal-data-in-the-gcc/)). No VAT *(background)*. Practo Insta already has a Kuwait hospital (ARGAN Riaya; [LinkedIn](https://www.linkedin.com/showcase/insta-by-practo), *secondary*). WhatsApp is costly: about USD 0.044 utility after the Oct 2026 rate change ([Meta](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing), [Chatmi](https://chatmi.io/en/blog/whatsapp-pricing-2026-guide), *secondary for the figure*). |
| **Bahrain** | PDPL (Law 30/2018) is in force. Sensitive data can be processed on consent without prior regulator permission ([HFW](https://www.hfw.com/insights/personal-data-in-the-gcc/)). VAT 10% *(background)*. Bahrain is pursuing a cloud-first, data-embassy approach ([Gulf Business](https://gulfbusiness.com/?p=316125)). |
| **Egypt** | Large, low-ARPU, Vezeeta's home market. **PDPL (Law 151/2020) Executive Regulations were issued as Ministerial Decree 816/2025 on 1 Nov 2025**, with a reported 1-year grace period, so compliance falls around Nov 2026 **⚠** ([Tamimi](https://www.tamimi.com/our-knowledge/publications/general/Articles/from-policy-to-practice-egypt-issues-executive-regulations-of-the-personal-data-protection-law/)). Health data is sensitive and needs licensing. WhatsApp utility about USD 0.0036, which is cheap *(secondary)*. VAT 14% *(background)*. |

---

## 2. Competitor landscape

### 2.1 Comparison table

Legend: WA = WhatsApp. "Verified current" means a 2025–26 source shows the product active.

| Product | Market / segment | Key features | Public pricing | WA | Queue / ETA | AI | Weaknesses / complaints | Verified current? |
|---|---|---|---|---|---|---|---|---|
| **Practo Ray** | IN (also GCC). Solo and small clinics | Appointments, EMR, billing, SMS, Practo marketplace listing, "Ray Connect" call handling | ₹999–1,999/month per clinic; Ray Connect ₹999–2,499 ([FitGap](https://us.fitgap.com/products/ray-by-practo)) | Reminders (SMS-first; WA depth unverified) | Appointment slots. No consult-duration ETA (unverified) | Limited | Capterra 4.0/5 (8 reviews); customer service 3.8 is the lowest sub-score; concerns about cloud data safety and limited reports ([Capterra](https://www.capterra.com/p/167778/Ray/reviews/)). Doctors distrust marketplace lock-in *(unverified)* | Yes. FY25 revenue ₹234 crore, EBITDA positive; IPO planned for 2026–27 ([Angel One](https://oga-prod.angelone.in/news/ipos/health-tech-firm-practo-initiates-bank-selection-for-2026-ipo)) |
| **Practo Insta HMS** | IN, UAE, KSA, Kuwait. Hospitals and multi-specialty | Full HMS, RCM, insurance, GCC e-claims | Quote | n/a | OPD queue (basic) | Limited | On-premise/legacy feel *(unverified)* | Yes. Dubai and Riyadh offices; GCC clients incl. Dar Afia and Emirates European Hospital ([Insta testimonials](https://instahms.com/testimonials), [Zoftwarehub](https://zoftwarehub.com/en-sa/products/insta/overview)) |
| **Eka Care** | IN. Doctors plus consumer PHR | EMR, ABDM (ABHA/PHR app), **EkaScribe** AI scribe (in-house "Parrotlet" model), integrates with third-party EMRs | ₹16,999–18,749/year per doctor; Clinic Pro ₹1L/year (5 doctors) ([SoftwareFinder](https://softwarefinder.com/emr-software/eka-care)) | Unverified | Unverified | **Strong**: ambient scribe, claimed >1 hour/day saved ([MedTech Spectrum](https://medtechspectrum.com/news/16/24807/eka-cares-ai-powered-ekascribe-transforms-clinical-documentation-for-doctors-across-india.html)) | Thin public reviews (Capterra 1 review at 1.0) | Yes. Listed on NABH's software page ([NABH](https://nabh.co/software/eka-care/)) |
| **HealthPlix** | IN. Solo and specialist EMR | Mobile EMR, AI CDS, drug-drug interactions, SmartScan, "Doctor Insights" | Not public. Widely believed to be free to doctors and pharma-monetised *(unverified)* | Rx sharing *(unverified)* | No | **CDS plus DDI** at point of care | Pharma-funded model raises neutrality and privacy questions *(unverified)* | Yes. Series C $22M (2023); 10K+ doctors ([MobiHealthNews](https://www.mobihealthnews.com/news/asia/indian-emr-startup-healthplix-scores-22m-series-c-funding)) |
| **DocOn** | IN. Clinics | EMR, appointments, telemedicine | ~USD 67/user/year ([SoftwareSuggest](https://www.softwaresuggest.com/us/docon)) | Unverified | Unverified | Unverified | Few reviews | Partial |
| **Bajaj Finserv Health** | IN. Consumer plus TPA (Vidal) | OPD plans, teleconsult, booking, TPA services | n/a | Yes (consumer) | No | n/a | Payer-led, not a clinic OS | Yes. Vidal acquired for ₹325 crore (2024) ([Digital Health News](https://www.digitalhealthnews.com/bajaj-finserv-arm-acquires-vidal-healthcare-services-in-a-deal-worth-inr-325-cr)) |
| **MediBuddy** | IN. Corporate OPD / consumer | Teleconsult, OPD benefits, lab/pharmacy | n/a | Yes | No | Unverified | Consumer aggregator, not an HMS | Status not re-verified this session *(unverified)* |
| **KareXpert** | IN plus some exports. Hospitals | Cloud HIMS, fixed monthly SaaS | Fixed monthly fee (not public) | Unverified | OPD module | Claims AI | Jio-backed; 350+ facilities ([KareXpert](https://www.karexpert.com/blogs/shifting-to-cloud-based-hims-helps-you-in-saving-costrecent-advancement-in-emerging-technologies-for-healthcare-management-software/)) | Yes (2026 listings) |
| **MocDoc** | IN, KSA/GCC. Clinics to hospitals | HMS, lab, pharmacy, **NPHIES integration** claimed | Per user, quote ([Capterra](https://www.capterra.in/software/161290/mocdoc-hms)) | Unverified | OPD | Unverified | Capterra 4.9 (44 reviews); NPHIES claims are vendor-only ([MocDoc](https://mocdoc.com/nphies-integration)) | Yes |
| **Ezovion HMS** | IN. Small hospitals | OPD/IPD, EMR, billing, telemedicine | Unclear (~USD 100/month listed) | Unverified | Basic | No | 1 public review ([Capterra](https://www.capterra.in/software/199966/ezovion-hms)) | Partial |
| **Paras HMS** | IN | Could not locate an authoritative listing | — | — | — | — | — | **(unverified)** |
| **Drlogy** | IN. Clinics and labs | Clinic + pathology lab software, QR registration, WA/SMS/email templates | From ₹299/month ([GetApp](https://www.getapp.co.uk/software/2072671/drlogy)) | **Yes** (templates) | No token queue found | Unverified | Breadth over depth *(unverified)* | Yes |
| **Clinicea** | UAE, KSA, IN. Clinics and polyclinics | EMR, NABIDH and ZATCA integrations | **AED 199 per practitioner per month**; ZATCA add-on USD 60/month ([Clinicea](https://clinicea.com/emr-management-software-dubai)) | Yes *(unverified depth)* | No ETA | Unverified | Generic UI; add-on fees | Yes |
| **TOKENN** (Cyberzio, Chennai) | IN. Small clinics | **WhatsApp-first token queue**: QR or WA message gives a token; live wait estimate; alert when 2 patients ahead; AI in 18 Indian languages | **~₹1 per patient** | **Native** | **Yes (live estimate)** | Language AI | Point solution; no EMR, Rx or HMS | Yes ([YourStory](https://yourstory.com/companies/tokenn), *secondary*) |
| **Qikwell / Docprime** | IN. Queue/booking | Qikwell was acquired by Practo (c. 2016); Policybazaar's Docprime raised $50M internally in 2018 ([YourStory](https://yourstory.com/2018/09/telemedicine-venture-docprime-gets-internal-funding-50-m-parent-policybazaar-group)) | — | — | Historic | — | Docprime's current status unclear | **(unverified)**. Treat both as defunct or absorbed |
| **Marg ERP** | IN. Retail pharmacy and distributors | Billing, inventory, GST, **Schedule H1 and narcotics registers** | ₹8,100 one-time + AMC; Cloud ~₹999/user/month ([SaaSRat](https://saasrat.com/products/marg-pharmaceutical-erp)) | Unverified | n/a | No | Desktop-era UX; dealer-led support *(unverified)* | Yes |
| **GoFrugal** | IN, ME. Retail/pharmacy POS | POS, inventory, mobile apps | Not found this session | Unverified | n/a | Unverified | — | **(pricing unverified)** |
| **PharmEasy / MedPlus retail tooling** | IN. Chain-owned | In-house retail stacks; PharmEasy (via Retailio) supplies B2B to pharmacies *(unverified)* | n/a | Yes (consumer) | n/a | — | Not sold as SaaS to independent pharmacies *(unverified)* | Partial |
| **ABDM Scan & Share** | IN. Public OPD | Govt QR-based OPD token via ABHA | Free | No | Token only, no ETA | No | Needs an HMIS to integrate; **it is a rail, not a competitor** ([MoHFW](https://www.mohfw.gov.in/press-info/7908)) | Yes |
| **Okadoc** | UAE, KSA. Patient booking marketplace | Booking/rescheduling, telemedicine, home care, cloud pharmacy | Free to patients | Unverified | No | Unverified | No 2024–26 news found; last round pre-Series B $10M (2022, Bupa Arabia, ADQ) ([Wamda](https://wamda.com/2022/11/okadoc-raises-10million-preSeriesb-funding-round-arabic-english)) | **Status 2026 unverified** |
| **Vezeeta** | Egypt, KSA, others. Marketplace + clinic SaaS | Booking, clinic SaaS (30K providers in 2020), pharmacy | n/a | Unverified | No | Unverified | 2022 layoffs (~10%) ([TechCrunch](https://techcrunch.com/2022/06/28/egyptian-healthtech-startup-vezeeta-cuts-10-of-500-person-staff)) | Yes (2026 partnership news, [Almal](https://almalnews.com/2121723/)) |
| **Altibbi** | Jordan, KSA, Egypt. Telehealth | Arabic medical content, telehealth, pharmacy | n/a | Unverified | No | Arabic AI triage *(unverified)* | Consumer telehealth, not clinic OS | Yes. Series B $44M (2022); 2025–26 "Next Health" programme ([AGBI](https://www.agbi.com/articles/altibbi-plans-healthcare-expansion-in-north-africa-and-pakistan/)) |
| **Meddy** | Qatar, UAE. Booking | Doctor booking | n/a | — | — | — | Acquired by Nigeria's Helium Health (Nov 2021) and folded into "Helium Doc" ([Wamda](https://www.wamda.com/en/2021/11/nigeria-based-helium-health-acquires-qatar-meddy)) | Absorbed |
| **Cura** | KSA. Telemedicine | Video consults; powered MOH's Sehha app (2.8M consults) | Per consult | — | — | — | Paediatrics and emergency focus | Partially. 2025 profile is third-party ([Gulf News](https://gulfnews.com/amp/business/markets/saudi-aramco-investment-arm-waed-signs-up-for-telehealth-startup-curas-sr15m-series-a-round-1.1634467692234)) |
| **Sehhaty / Seha Virtual Hospital** | KSA govt | Booking, medical record, sick leave, Rx; unified Mawid, Tetamman and others; 31M users | Free | No | Public slots | Unverified | Public sector only; no private-clinic integration found ([Al Riyadh](https://alriyadhdaily.com/article/ffb46c2aa8c040e39f225fc6e3e4d910)) | Yes |
| **Clinicy** | KSA. Clinics | Booking, notifications, **WhatsApp**, campaigns, loyalty | Not public | Yes | Unverified | Unverified | Patient app "coming soon" ([Yespress](https://yespress.io/clinicy)) | Yes |
| **Unite EMR** | UAE. Clinics | Listed as NABIDH-ready | Not public | Unverified | Unverified | Unverified | Listed by a competitor (Pabau) ([Pabau](https://pabau.com/blog/emr-software-dubai/)) | Partial |
| **Medware / "Medas"** | UAE/GCC | Could not locate authoritative 2025–26 sources | — | — | — | — | — | **(unverified)** |
| **InterSystems TrakCare / HealthShare** | UAE federal, GCC public | Underpins the Riayati NUMR (with PureCS, Apelon) | Enterprise | — | — | — | Public-sector enterprise ([Intelligent CIO](https://www.intelligentcio.com/me/?p=60429)) | Yes |
| **Oracle Health (Cerner)** | Qatar (HMC), UAE (SEHA RCM, EHS), KSA (MoD, universities) | Enterprise EHR/RCM | Enterprise | — | — | Oracle AI agents *(unverified in GCC)* | Lost a net 56 hospitals globally in 2025; 21.9% share ([Fierce Healthcare](https://www.fiercehealthcare.com/health-tech/epic-continues-grow-ehr-market-share-it-makes-gains-small-health-systems?rand=57452), [Becker's](https://beckershospitalreview.com/ehrs/oracle-cerner-expands-relationships-with-global-health-systems.html)) | Yes |
| **Epic** | UAE, KSA high end | Enterprise EHR; 43.7% US acute share | Enterprise | — | MyChart queueing | Strong | Out of reach below about 200 beds | Yes ([Black Book](https://www.accessnewswire.com/newsroom/en/healthcare-and-pharmaceutical/u.s.-ehr-giants-drive-global-healthcare-digital-transformation-across-960888)) |

*Tabby-like BNPL was skipped as irrelevant to the core product. It may matter later for elective and dental payments in the GCC.*

### 2.2 Observations

- **No incumbent ships a consult-duration-aware ETA end to end.** Practo, Eka, HealthPlix and Clinicea manage *appointments*. ABDM manages *tokens*. TOKENN and ClinicFlow ([ClinicFlow](https://lqmclinicflow.lovable.app/)) show a live wait, but as point tools without clinical depth. Most tools estimate the wait as queue position × average consult time. A per-doctor, per-visit-type duration model (new vs follow-up, procedure, time-of-day drift) with live recalculation is a credible technical edge.
- **The market splits at around 10 doctors.** Clinic tools (Ray, Eka, Drlogy) do not grow into IPD, pharmacy or lab. HMS vendors (Insta, KareXpert, MocDoc) are too heavy for a solo doctor. A **modular single platform** that switches modules on per tenant lets a doctor keep the same identity and UX from a solo practice to a chain.
- **AI is table stakes in India.** EkaScribe (scribe) and HealthPlix (CDS + DDI) have set expectations. Nabd's AI should be framed as *safety* (allergy, condition and interaction checks against a licensed drug knowledge base) plus *time* (scribe). It should not be framed as diagnosis, for SaMD reasons (Section 3.6).
- **The GCC moat is compliance plumbing.** Clinicea and MocDoc market NABIDH, ZATCA and NPHIES integrations as features. There is no public DHA list of NABIDH-connected vendors, and the facility's licence depends on an approved EMR connection ([Pabau](https://pabau.com/blog/emr-software-dubai/)). Certification is therefore a sales prerequisite.
- **Consented cross-facility sharing is still unbuilt in the private market.** India's HIE-CM provides consent artefacts with expiry, but developers report unreliable expiry callbacks ([ABDM dev forum](https://devforum.abdm.gov.in/t/not-receiving-expiry-notification-once-a-granted-consent-gets-expired/1647)). The GCC HIEs are government-to-facility pipes and do not give patients granular, time-limited consent. Nabd's audited, time-boxed doctor+patient consent model is differentiated. It must be **layered on** the national rails, not compete with them.

### 2.3 White space Nabd can own

| Wedge | Why it is open | Proof needed |
|---|---|---|
| **Live queue ETA on WhatsApp** ("you are #7, about 38 min; leave home in 15 min") | Incumbents have appointments, not ETAs. Point tools lack clinical data | ETA mean absolute error under 10 min in pilots; fewer patients in the waiting room |
| **Solo to hospital continuum** | Clinic and HMS markets are separate products today | One codebase; modules toggled per tenant |
| **Consented, time-limited interop** | Rails exist (ABDM, NABIDH, Malaffi), but no UX for patient/doctor consent | Audit trail; consent expiry enforced server-side, not via callbacks |
| **Global doctor profile, facility-bound records** | Doctors work across 2–4 facilities; current tools are per-clinic | HPR (India) and DHA/DoH licence IDs as anchors |
| **Pharmacy module sellable standalone** | Marg-era tools are desktop and dealer-led; no Rx-to-pharmacy loop | H1 register, GST, e-Rx ingestion, WhatsApp refill reminders |

---

## 3. Regulation and compliance

### 3.1 India

**DPDP Act 2023 and DPDP Rules 2025 ⚠ in transition**
- **Notified 13 Nov 2025.** Rules 1, 2 and 17–21 (definitions, Data Protection Board) took effect immediately. **Rule 4 (Consent Managers) takes effect 12 Nov 2026**, which is next month. **Rules 3, 5–16, 22 and 23 take effect around 12–13 May 2027** ([AZB & Partners](https://www.azbpartners.com/?p=87799), [Deccan Herald](https://www.deccanherald.com/india/centre-notifies-dpdp-rules-implementation-planned-in-phases-spread-over-12-18-months-3798465)).
- Key substantive rules, per the [Protiviti rule-by-rule summary](https://www.protiviti.com/sites/default/files/2025-11/flash_compliance_update_dpdp_rules-2025.pdf) (*secondary; verify against the Gazette*):
  - R3: standalone, itemised notice.
  - R6: reasonable security, including encryption, access control, logging and backups, with **logs retained for 1 year** ([Lakshmikumaran & Sridharan](https://www.lkslaw.com/insights/news/draft-digital-personal-data-protection-rules-2025-released)).
  - R7: breach notice to the Board and affected individuals **without delay, with a detailed report within 72 hours**.
  - R8: erasure after purpose ends, with **48 hours' notice** to the individual before deletion.
  - R10/R11: verifiable parental or guardian consent.
  - R12 / Fourth Schedule: exemptions for children's data at **clinical establishments and healthcare professionals**, limited to health-service purposes ([EY](https://www.ey.com/content/dam/ey-unified-site/ey-com/en-in/pdf/2025/11/dpdp-act-and-rules.pdf)).
  - R13: Significant Data Fiduciaries need an annual DPIA and audit, and possible localisation of specified data.
  - R15: cross-border transfer allowed unless restricted by government (a negative-list approach).
- **Penalties** go up to ₹250 crore (security failures), ₹200 crore (breach notification or children), ₹150 crore (failure to erase) and ₹50 crore (other).
- **Implication:** Nabd is a *Data Processor* for clinic records and a *Data Fiduciary* for the patient aggregator and global profiles. Plan for possible SDF designation, given the volume and sensitivity of health data *(estimate)*.

**Cyber incident reporting (CERT-In).** CERT-In Directions of 28 Apr 2022 require reporting cyber incidents **within 6 hours** and keeping ICT logs for 180 days in India ([CERT-In](https://www.cert-in.org.in/PDF/CERT-In_Directions_70B_28.04.2022.pdf); *from background knowledge, not re-fetched this session*).

**ABDM (Ayushman Bharat Digital Mission)**
- **Building blocks:** ABHA (patient ID), HFR (facility registry), HPR (professional registry), HIE-CM (consent manager, consent artefacts with expiry and `dataEraseAt`), and HIP/HIU roles. Data moves as encrypted HL7 FHIR R4 bundles using NRCeS/NDHM profiles.
- **Milestones:**
  - **M1:** ABHA creation, verification and linking, plus Scan & Share.
  - **M2:** act as HIP. Care contexts, discovery, linking, consent-gated push of FHIR records.
  - **M3:** act as HIU. Request consent and pull records.
  - **M4** (some sources): NHCX claims.
- **Process:** sandbox → functional test → security audit by an empanelled agency → NHA demo → production keys ([Nirmitee guide](https://nirmitee.io/blog/abdm-certification-process-sandbox-to-production-guide/), [case study](https://nirmitee.io/case-study/abdm-sandbox-exit-m1-m2-m3-screening-platform-case-study/), *secondary*; official sandbox: https://sandbox.abdm.gov.in).
- Developers report version confusion and flaky consent-expiry callbacks ([dev forum](https://devforum.abdm.gov.in/t/regarding-m1-m2-m3-testcases/7525)). **Design rule:** enforce consent expiry ourselves.
- **NABH HIS/EMR certification** requires at least **3 live hospital deployments plus a valid ABDM M3 certificate**. It is valid for 2 years ([NABH](https://nabh.co/programmes/digital-health-standards-his-emr-systems/), [FAQ](https://nabh.co/faqs-digital-health-standards/)). **NABH Digital Health Standards 2nd Edition (Sep 2025)** exists in draft or released form **⚠** ([NABH portal PDF](https://portal.nabh.co/Announcement/Draft%20NABH%20DHS%202nd%20Edition.pdf)).

**Telemedicine Practice Guidelines 2020.** Only a registered medical practitioner may prescribe.
- **List O:** OTC drugs; any mode.
- **List A:** first prescription only after a video consult.
- **List B:** add-on drugs for follow-up patients only.
- **Prohibited:** Schedule X and NDPS drugs ([Khaitan](https://khaitanco.com/thought-leaderships/Telemedicine-Practice-Guidelines), [Mondaq](https://www.mondaq.com/india/healthcare/921874/telemedicine-practice-guidelines)).

The product must tag drugs by list and block prohibited items in tele-consults.

**E-prescriptions and professional conduct.** Prescriptions must meet the Drugs & Cosmetics Rules and NMC ethics regulations. The **NMC 2023 Professional Conduct Regulations** (generic prescribing, 3-year record keeping, 5-day record access) were **put in abeyance in Aug 2023** ([LawBhoomi](https://lawbhoomi.com/who-owns-medical-records-patient-or-hospital/), *verify*). The **2002 IMC Regulations** therefore remain the operative baseline: **inpatient records for 3 years**, records supplied within 72 hours on request ([Medical Dialogues](https://medicaldialogues.in/health-news/nmc/doctors-need-to-maintain-patient-record-for-3-years-nmc-draft-94681)). E-pharmacy rules (2018 draft) are still **not notified** ([MNLU Law Review](https://lawreview.mnlumumbai.edu.in/?p=360), [LSHTM](https://www.lshtm.ac.uk/media/102426)) **⚠**.

**Pharmacy (Drugs & Cosmetics Rules 1945)**
- Schedule H, H1 and X drugs are sold only against a registered practitioner's prescription (Rule 65(9)).
- The **H1 register** (prescriber, patient, drug, quantity) must be **kept for 3 years** and open to inspection ([CliniExperts on the 2013 amendment](https://cliniexperts.com/fourth-amendment-in-drugs-cosmetics-rules-2013/)).
- For **Schedule X**, keep a prescription copy and records for 2 years *(secondary; verify)*.
- NDPS registers apply for narcotics ([NHSRC](https://www.nhsrcindia.org/sites/default/files/2021-07/Attachment%20A%2C%20Schedule%20H1%20register%20and%20NDPS%20Forms.pdf)).

**AI CDS as SaMD.** CDSCO issued **draft guidance on medical device software on 21 Oct 2025**. It applies risk-based classification (Class A–D) under the MDR 2017, based on the significance of the information and the seriousness of the condition ([Corpseed](https://www.corpseed.com/law-update/cdsco-updates-draft-guidance-for-medical-device-software-regulations), [Conventus Law](https://conventuslaw.com/report/india-medical-device-as-software-has-cdsco-guidance-changed-the-rules/)) **⚠**. Treatment-directing CDS could fall into Class B, C or D.

**Clinical Establishments Act 2010.** Adopted by a subset of states; Karnataka and Telangana run their own regimes *(unverified)*. Registration standards vary, and state rules may set longer record retention (5–10 years is commonly quoted; *unverified*).

**Data localisation.** There is no general health-data localisation mandate in India today. The DPDP takes a negative-list approach, SDFs may face localisation, and CERT-In logs must be kept in India. **Recommendation:** host Indian tenants in India regardless (AWS Mumbai/Hyderabad or Azure Pune/Chennai).

**Tax.**
- Healthcare services by clinical establishments and authorised medical practitioners are **GST-exempt** (Notification 12/2017-CT(Rate), Entry 74) ([TaxGuru](https://taxguru.in/goods-and-service-tax/gst-health-care-services.html)).
- Since 22 Sep 2025 (GST 2.0), **most medicines are 5%** (from 12%), with 33 life-saving drugs at 0% and many devices and diagnostics at 5% ([Bajaj Finserv](https://www.bajajfinserv.in/gst-on-medicines), [Busy](https://busy.in/gst/gst-2-0-healthcare-5-18-rates)).
- Nabd's SaaS fee is subject to 18% GST *(standard IT-services rate; verify post-GST 2.0)*. Clinics cannot claim input credit, because their output is exempt. **18% is therefore a real cost to the clinic**, and India prices should be quoted inclusive of GST.
- The pharmacy module needs HSN-wise GST and e-invoicing for B2B above the turnover threshold *(verify current threshold)*.

### 3.2 UAE

- **Federal Law No. 2 of 2019 (ICT in Health Fields)**
  - **Art. 13:** health data may not be stored, processed, generated or transferred outside the UAE except as permitted by health-authority resolution.
  - **Resolution 51/2021** (in force 16 May 2021) sets out 10 exception categories, approved case by case: treatment abroad, insurance, clinical trials, telemedicine, pharmacovigilance, wearables, diagnostics and others ([Tamimi](https://www.tamimi.com/law-update-articles/setting-healthy-boundaries-regulated-exemptions-to-restrictions-on-the-transfer-the-health-data-outside-of-the-uae/), [Kayrouz](https://www.kayrouzandassociates.com/insights/cross-border-data-transfers-under-uae-law-in-2026)).
  - **Art. 20:** keep health data **at least 25 years from the last procedure** ([Latham](https://lw.com/thoughtLeadership/lw-new-uae-law-regulates-healthcare-data)).
  - **Implication: UAE tenants must run in a UAE cloud region** (Azure UAE North, AWS me-central-1, Oracle Abu Dhabi/Dubai, G42/Core42; *availability per authority to be checked*). That includes AI inference on PHI, so LLM calls must go to in-country endpoints or be de-identified.
- **PDPL (Federal Decree-Law 45/2021)** has been in effect since 2 Jan 2022. It **excludes health data covered by sectoral law**. Executive Regulations were **still unpublished as of Feb 2026**, and a 1 Jan 2027 full-compliance date is widely cited **⚠** ([Kayrouz](https://www.kayrouzandassociates.com/insights/cross-border-data-transfers-under-uae-law-in-2026), [ConsentStack](https://consentstack.io/regulations/uae-pdpl)). It still applies to non-health personal data in the aggregator (marketing, profiles).
- **DHA (Dubai)**
  - Facility licensing runs through Sheryan.
  - **NABIDH HIE** compliance is mandatory for all DHA-licensed facilities. Standards: HL7 v2.5.1 (common in practice) and FHIR R4 (recommended) ([DHA NABIDH standards PDF](https://www.dha.gov.ae/uploads/102022/NABIDH%20Policies%20&%20Standards%20-%20Interoperability%20and%20Data%20Exchange%20Standards2022100429.pdf)).
  - Connection goes through an approved EMR vendor, using the NABIDH EMR code, facility licence ID and app key ([Clinicea](https://clinicea.com/blog/nabidh-integration-service)).
  - Claims go through **DHPO/eClaimLink**. Dubai Drug Codes (>14,000 coded) are mandatory on claims ([DHA](https://www.dha.gov.ae/en/media/news/867), [eClaimLink DDC](https://www.eclaimlink.ae/dhd_codes.aspx)).
- **DoH Abu Dhabi**
  - **Malaffi**: Circular 2026/10 (19 Jan 2026) requires all facilities to transmit minimum datasets within 6 months (rest of text not retrieved) **⚠**.
  - The EMR must be Malaffi-interoperable, and onboarding is a licensing prerequisite ([Tamimi](https://tamimi.com/news/malaffi-emr-integration-required-for-all-abu-dhabi-healthcare-facilities)).
  - **ADHICS v2** has been effective since Aug 2024. It applies to facilities **and healthcare technology providers**, and requires full localisation, with renewable 1-year exemptions via the AAMEN portal ([Qualysec](https://qualysec.com/adhics-guidelines/), [Kayrouz](https://www.kayrouzandassociates.com/insights/cross-border-data-transfers-under-uae-law-in-2026)).
  - **Shafafiya** e-claims: ICD-10-CM diagnoses (DG 44/15), real-time submission (DG/17/17), mandatory dose/frequency/RxInstruction fields in PBM claims ([DoH notice](https://www.doh.gov.ae/en/Shafafiya/notices/dose-frequency-and-rxinstruction-in-pbm-prior-authorisations-and-claims)).
- **MOHAP / EHS: Riayati** National Unified Medical Record (built on InterSystems). Facilities licensed by MOHAP had to join within a year. It connects >3,000 facilities and links to NABIDH and Malaffi ([MOHAP](https://mohap.gov.ae/en/riayati/about-numr)).
- **Emirates ID / UAE PASS.** Emirates ID is the patient identifier on claims and in the HIEs *(practice; verify field mandates per authority)*. UAE PASS for patient login is a nice-to-have.
- **Tax and e-invoicing.**
  - VAT 5%. Preventive and treatment healthcare is **zero-rated**. Elective and cosmetic services are 5% ([PwC](https://www.pwc.com/m1/en/tax/vat-in-the-middle-east/pdf/implementation-flyers/how-vat-impacts-you-healthcare.pdf)).
  - **UAE e-invoicing (Peppol, 5-corner model)** under Ministerial Decisions 243/244 of 2025: B2C is excluded, but clinic→insurer invoices are B2B. Businesses with **revenue of AED 50M or more must comply from 1 Jan 2027**; others from 1 Jul 2027. The provider-appointment deadline may have moved to 30 Oct 2026 (MD 66/2026, *unverified*) **⚠** ([KPMG](https://kpmg.com/us/en/taxnewsflash/news/2025/10/uae-framework-scope-implementation-e-invoicing-system.html), [Fiscal Requirements](https://www.fiscal-requirements.com/news/5357-uae-e-invoicing-legal-framework-and-implementation-updated-2026)).
- **Coding.** ICD-10-CM and CPT (Dubai, Abu Dhabi), DRG for inpatient *(CPT/DRG details unverified)*.

### 3.3 Saudi Arabia

- **PDPL (SDAIA)**
  - Fully enforceable since **14 Sep 2024**. Health data is "sensitive data".
  - **Breach notice to SDAIA within 72 hours**, and to individuals without undue delay ([Tamimi](https://www.tamimi.com/news/observations-and-tips-on-data-issues-in-ksa/), [RSM](https://www.rsm.global/saudiarabia/sites/default/files/media/documents/RSM_PDPL_Flyer_Updated_2026.pdf)).
  - Fines up to SAR 5M, doubled for repeat offences.
  - The **amended Transfer Regulation (1 Sep 2024)** allows transfers to adequate countries (list pending) or under SCCs, BCRs or certification. A risk assessment is required for continuous or large-scale transfers of sensitive data ([Baker McKenzie](https://insightplus.bakermckenzie.com/bm/data-technology/saudi-arabia-updates-data-transfer-regulations-and-introduces-the-first-set-of-standard-contractual-clauses), [Clyde & Co](https://www.clydeco.com/en/insights/2024/09/saudi-arabia-s-personal-data-protection-law-become)).
  - SDAIA published non-binding transfer risk-assessment guidelines on 25 Feb 2025.
- **NCA controls.** **ECC-2:2024** and **CCC-2:2024** (updated for data localisation) apply to government and CNI, and through their supply chains. They are "strongly encouraged" for others ([NCA](https://nca.gov.sa/en/regulatory-documents/controls-list/ccc/)). Hospitals in clusters or under the Health Holding Company are government-linked, so **plan for in-Kingdom hosting** (e.g. Google Cloud Dammam, Oracle Jeddah/Riyadh, STC Cloud, AWS/Azure KSA regions as available; *verify*).
- **NPHIES (CHI/NHIC).** National FHIR-based platform for eligibility, prior authorisation, claims and payment reconciliation, mandatory for insured care ([Healthcare IT News](https://www.healthcareitnews.com/news/emea/understanding-nphies-ksas-health-information-exchange), [Simplifier SA](https://api.simplifier.net/jurisdictions/SA)). Coding: **ICD-10-AM 10th ed + ACHI + ACS + AR-DRG v9**, and the **Saudi Billing System (SBS)**, which extends ACHI to 7,947 codes and is required for NPHIES billing ([BMC HSR 2025](https://biomedcentral-bmchealthservres.publicaciones.saludcastillayleon.es/articles/10.1186/s12913-025-12652-6)).
- **Wasfaty** (MOH e-prescription). Described as mandatory for doctors and pharmacies in MOH-linked care ([Frontiers 2024](https://www.frontiersin.org/journals/medicine/articles/10.3389/fmed.2024.1487852/pdf)). Nahdi opened Wasfaty at all its pharmacies on 1 Oct 2025 ([Argaam](https://www.argaam.com/en/article/articledetail/id/1848788)). Private-clinic obligations are **(unverified)**.
- **SFDA.** **RSD/DTTS drug track-and-trace**: GTIN + serial in a GS1 DataMatrix, GLN locations. Pharmacies must report dispense events via integration, CSV or the portal ([SFDA RSD manual](https://rsd.sfda.gov.sa/user-manual/SMP-manual/index_en-US.html), [SFDA Integration Guide v2.1](https://sfda.gov.sa/sites/default/files/2024-08/IntegrationGuideDTTSV21.pdf)). Any pharmacy module in KSA needs RSD integration. **AI CDS:** SFDA **MDS-G010** covers ML-based SaMD *including CDS* ([Tamimi](https://tamimi.com/law-update-articles/new-guidance-for-ai-and-big-data-medical-devices-in-saudi-arabia), [SFDA](https://www.sfda.gov.sa/en/regulations/87661)). A newer digital-health guidance (MDS-G27, Aug 2025) is reported but *unverified*.
- **Sehhaty / Seha Virtual Hospital.** The public patient front door. There is no private-clinic booking integration we could find. Treat it as a channel *to watch*, not one to build on.
- **ZATCA Fatoora Phase 2** ⚠
  - Wave 23: SAR 750k, 31 Mar 2026.
  - Wave 24: SAR 375k, 30 Jun 2026.
  - **Wave 25** (announced 24 Jul 2026): taxpayers with VAT-able revenue **above SAR 187,500 in any year 2022–2025** must integrate by **1 Feb 2027** ([KPMG](https://kpmg.com/us/en/taxnewsflash/news/2026/07/saudi-arabia-e-invoicing-mandatory-for-taxpayers-with-revenue-above-sar-187500.html), [Qoyod](https://www.qoyod.com/en/knowledge-base/e-invoicing/e-invoicing-phase-2-waves/)).
  - Effectively **every VAT-registered clinic**. Simplified invoices go to patients at reception (reporting), and standard invoices go to insurers (clearance) ([MocDoc blog](https://mocdoc.com/blog/nphies-integrated-clinic-management-software)).
- **VAT 15%.** Private healthcare for **Saudi nationals is 0% (the state bears it)**, and 15% for non-Saudis. This requires a nationality-driven tax engine and split-line invoices ([ZATCA Healthcare Guide](https://zatca.gov.sa/en/HelpCenter/guidelines/Documents/VAT%20%20Healthcare%20Guide%20English.pdf), [Cleartax](https://www.cleartax.com/sa/vat-on-healthcare)).
- **Medical record retention:** not found **(unverified)**. The rule is probably in the Arabic annexes of the Private Health Institutions Law regulations ([MOH](https://www.moh.gov.sa/en/eservices/licences/pages/private-health-institutions-law-and-regulations.aspx)). Default to the UAE-style 25 years until counsel confirms.

### 3.4 Qatar

- **Law No. 13 of 2016 (PDPPL)** is enforced by NCSA's National Cyber Governance and Assurance Affairs (NCGAA). Health data is "special nature" data that reportedly needs **prior permission** to process. Breaches causing serious damage must be notified to the regulator and individuals "as soon as possible". A 72-hour figure is **(unverified)**. Fines are QAR 1–5M ([DLA Piper](https://www.dlapiperdataprotection.com/?c=QA&t=law), [Clym](https://www.clym.io/regulations/law-no13-of-2016-qatar)). NCSA released a cloud privacy assessment tool in Apr 2026 ([MEA Tech Watch](https://meatechwatch.com/2026/04/09/qatar-launches-cloud-privacy-assessment-tool-for-organisations/)).
- **Residency.** Vendor sources claim MOPH requires in-country storage of health data ([Kiteworks](https://www.kiteworks.com/regulatory-compliance/qatar-healthcare-data-residency-compliance/), *secondary*). Others note that Qatar's cloud policy is classification-based rather than blanket localisation ([InCountry](https://incountry.com/blog/qatar-data-protection-law-guide-for-global-companies)). **(unverified). Assume in-country** (Azure Qatar Central, Google Cloud Doha).
- **Context.** HMC (Oracle Cerner, nationwide CIS) and PHCC dominate. National Health Strategy 2024–2030 ([Qatar Tribune](https://qatar-tribune.com/article/140162/front/pm-launches-national-health-strategy-2024-2030)). The private market is growing on mandatory expat insurance.
- **Tax.** No VAT implemented *(background; re-verify before launch)*.

### 3.5 Interoperability and terminology standards by market

| Standard | India | UAE | KSA | Qatar |
|---|---|---|---|---|
| HL7 FHIR R4 | ABDM HIP/HIU bundles (NRCeS profiles); NHCX claims | NABIDH (FHIR R4 recommended; HL7 v2.5.1 widely used); Malaffi; Riayati | NPHIES (Saudi FHIR IG) | HMC/Cerner. Private HIE requirements (unverified) |
| Diagnosis coding | ICD-10 (WHO). ICD-11 not mandated *(unverified)* | ICD-10-CM | ICD-10-AM 10th ed | ICD-10-AM *(unverified)* |
| Procedure / billing | Local tariffs; NHCX/PMJAY packages | CPT (+ DRG IP) *(unverified detail)* | ACHI + SBS; AR-DRG v9 | (unverified) |
| Labs | LOINC in ABDM DiagnosticReport profiles *(unverified)* | LOINC (NABIDH) *(unverified)* | LOINC via NPHIES *(unverified)* | (unverified) |
| Clinical terms | SNOMED CT: India has a national release via NRCeS *(unverified)* | SNOMED CT | SNOMED CT | SNOMED CT (Cerner) |
| Drug database | CIMS/MIMS India (formulary mapping, interaction and dosage checks; [MIMS](https://corporate.mims.com/country_office/india)); CDSCO lists | **Dubai Drug Codes** (DHA), DoH drug codes, MIMS ME | **SFDA drug registry, GTIN/RSD**, Saudi Drug Code *(unverified name)* | MOPH formulary *(unverified)* |

Note: DDI databases disagree with each other. A 2026 ISPOR abstract found variability across DDI sources in Indian oncology ([ISPOR](https://www.ispor.org/heor-resources/presentations-database/presentation-cti/ispor-europe-2026/poster-session-5-4/variability-in-drug-drug-interaction-databases-and-its-implications-for-clinical-decision-support-in-oncology-a-cross-sectional-prescription-audit-from-india)). Licence one primary knowledge base per region and show the source in the UI.

### 3.6 Language, calendar and UX requirements

- **Arabic and RTL across the whole product** (UI, prescriptions, invoices, WhatsApp templates) for the GCC. Bilingual (Arabic + English) printouts are the norm. ZATCA invoices must carry Arabic *(verify field rules in the ZATCA XML spec)*.
- **Hijri dates.** Show Hijri alongside Gregorian in KSA (government, sick-leave and some ID documents). Store dates as Gregorian/ISO and display both.
- **India:** English plus at least Hindi, Kannada, Telugu, Tamil and Marathi for patient-facing WhatsApp flows. Doctors work in English.
- **Patient identifiers:** India uses ABHA (optional), Aadhaar (do not store numbers unnecessarily) and mobile number. The UAE uses Emirates ID. KSA uses National ID or Iqama. Qatar uses QID.

---

## 4. Compliance requirements matrix

| Requirement | India | UAE | KSA | Qatar | What Nabd must do |
|---|---|---|---|---|---|
| **Health-data residency** | Not mandated (DPDP negative list; SDF may face localisation); CERT-In logs in India | **Mandatory in-UAE** (FL 2/2019 Art 13); ADHICS full localisation in AD | Effectively mandatory for govt-linked/CNI (NCA CCC-2:2024); PDPL transfer conditions | Assume in-country **(unverified)** | **One data region per tenant** (`tenant.region`), pinned at onboarding; no PHI replication across regions; AI inference in region |
| Cross-border transfer | Allowed unless restricted (R15) | Only via Res. 51/2021 exceptions + authority approval | Adequacy / SCC / BCR / certification + risk assessment for sensitive data | Permission-based **(unverified)** | Transfer register; block cross-region record share unless an exception workflow is approved |
| **Consent for cross-facility sharing** | ABDM HIE-CM consent artefact (purpose, HI types, date range, expiry, `dataEraseAt`) | Via NABIDH/Malaffi/Riayati policies; patient opt-out models *(unverified)* | PDPL consent for sensitive data; NPHIES for payer flows | PDPPL permission | Nabd consent object: patient + doctor approval, scope, expiry, server-side enforced revocation and erasure; map to ABDM artefacts |
| Consent managers | Rule 4 from **12 Nov 2026** | — | — | — | Integrate with registered DPDP Consent Managers (API) by 2027 |
| **Breach notification** | Board + individuals without delay; detailed report **72 h** (from May 2027); **CERT-In 6 h** | PDPL: "without delay" to Data Office *(unverified for health)*; DoH/DHA incident rules | **SDAIA 72 h**; individuals without undue delay | Regulator + individuals "as soon as possible" | Incident runbook with the strictest clock (6 h) globally; per-tenant notification templates |
| **Audit logs** | Logs ≥ **1 year** (DPDP R6); CERT-In 180 days | ADHICS logging *(retention unverified)* | NCA ECC logging *(retention unverified)* | NCSA | Immutable, tenant-scoped audit log of every PHI read/write/share; keep at least 1 year hot and as long as the record cold |
| **Medical record retention** | IP records ≥ 3 years (IMC 2002); state rules may be longer; MLC longer | **≥ 25 years** from last procedure | **(unverified)**; default 25 y | (unverified) | Configurable per-jurisdiction retention; legal holds; DPDP erasure must respect sectoral retention |
| Erasure notice | 48 h before erasure (R8) | — | PDPL destruction rules | — | Scheduled-deletion notifications |
| Children's data | Verifiable parental consent; clinical-establishment exemption (Fourth Schedule) | PDPL *(n/a for health)* | Guardian consent | — | Guardian linkage on minor profiles; age gating in aggregator |
| DPO / privacy contact | Publish contact (R9); SDF needs India-based DPO | PDPL DPO in certain cases | DPO for large-scale sensitive data *(verify)* | — | DPO role per region |
| **HIE connection** | ABDM M1/M2/M3 (voluntary; needed for NABH HIS cert and hospital RFPs) | **NABIDH** (Dubai), **Malaffi** (AD), **Riayati** (MOHAP): licensing prerequisite | NPHIES (payer); MOH HIE *(unverified for private)* | (unverified) | Build FHIR R4 core and adapters per HIE; obtain certifications |
| E-prescription | TPG 2020 lists; Schedule X prohibited in telemedicine | DHA/DoH e-Rx (PBM fields mandatory in AD claims) | **Wasfaty** (MOH); private scope unverified | MOPH *(unverified)* | Rx engine with jurisdiction drug lists, expiry, refills, QR verification |
| Controlled-drug registers | **H1 register 3 years**; Schedule X 2 y *(verify)*; NDPS | DHA/DoH narcotics rules *(unverified)* | SFDA narcotics + **RSD track & trace** | MOPH *(unverified)* | Pharmacy module: registers, GTIN/serial scanning (KSA), audit |
| **E-claims** | NHCX (FHIR) for hospitals; TPAs | **DHPO/eClaimLink**, **Shafafiya** | **NPHIES** | Insurer portals *(unverified)* | Claims adapters per payer platform; code sets per market |
| **E-invoicing** | GST e-invoice (B2B, above threshold) | Peppol B2B from **1 Jan 2027** (≥ AED 50M) / **1 Jul 2027** others | **ZATCA Phase 2**: Wave 25 by **1 Feb 2027** | None | Tax engine per country; ZATCA clearance/reporting SDK; Peppol ASP partner |
| Indirect tax | Healthcare exempt; medicines 5%; SaaS 18% | 0% healthcare (5% elective); 5% std | 15%; **0% for Saudi nationals** (state-borne) | None | Nationality-aware tax lines (KSA); exempt/zero-rated flags |
| AI CDS | CDSCO SaMD draft (Oct 2025) | DoH AI policy *(unverified)* | **SFDA MDS-G010** | (unverified) | Position as "information to clinician, clinician decides"; QMS (ISO 13485/IEC 62304) roadmap if classed as a device |
| Cyber standard | CERT-In; ISO 27001 expected | **ADHICS v2** (applies to tech providers) | **NCA ECC/CCC** | NCSA NIA | ISO 27001 + SOC 2 baseline; ADHICS/NCA gap assessments |
| Language / calendar | Indic languages for patients | Arabic + English, RTL | Arabic + English, RTL, **Hijri** | Arabic + English, RTL | i18n from day one; RTL-safe components; dual calendar |
| WhatsApp opt-in | Meta policy (opt-in) + DPDP consent | Meta + PDPL | Meta + PDPL | Meta + PDPPL | Record opt-in per number; separate marketing consent |

---

## 5. Go-to-market recommendation

### 5.1 Which market first: **India (Bengaluru + Hyderabad)**

| Criterion | India | UAE (Dubai) | KSA |
|---|---|---|---|
| Core pain (queue/time) | **Very high**: walk-ins, outdoor queues, unpredictable waits | Medium (appointment culture, air-conditioned waiting rooms) | Medium |
| Time to first revenue | **Weeks** (no licence gate for SaaS) | 3–6 months (NABIDH vendor onboarding, DHPO) | 6–12 months (NPHIES, ZATCA, Arabic, residency) |
| ARPU per doctor/month | ₹999–1,499 (~USD 12–18) | AED 199–349 (~USD 54–95) | SAR 249–399 (~USD 66–106) |
| WhatsApp cost per visit *(estimate)* | ~₹0.2–0.5 | ~USD 0.02–0.03 | ~USD 0.01–0.02 |
| Competitive intensity | High but fragmented | High (Clinicea, Unite, Insta, Pabau and more) | Medium (local players + MocDoc/Insta) |
| Regulatory build before launch | DPDP-ready design; ABDM optional | UAE hosting + NABIDH + e-claims | KSA hosting + NPHIES + ZATCA + SFDA RSD |
| Founder/network fit | (assumed) | Indian-diaspora polyclinics | Later |

**Rationale.** India is where the queue pain is most visible and where the ETA feature can prove itself fastest. It is also where we can iterate weekly without certification gates. The team is building in India and can ship with Indian hosting. The ARPU gap is real, which is why the **architecture must be GCC-ready from day one** (tenant region, Arabic/RTL, FHIR core, tax engine abstraction). The UAE is the logical second market: higher ARPU, a familiar buyer persona (South Asian-run polyclinics) and an English-friendly setting. It still requires UAE hosting and NABIDH from the first customer.

### 5.2 Beachhead segment

**Solo and 2–5-doctor specialty clinics in Bengaluru and Hyderabad with 40 or more walk-ins a day**: paediatrics, gynaecology/obstetrics, ENT, dermatology, orthopaedics and general medicine. Prioritise clinics that already hand out paper tokens and have a WhatsApp number on their signboard.

- **Wedge product:** WhatsApp booking and walk-in check-in, with live ETA ("#7, about 38 minutes"), "leave now" nudges, and a doctor-side single-tap "next patient" that trains the duration model. **Self check-in by QR is the default for solo doctors with no receptionist.**
- **Land and expand:** Rx (with allergy, condition and interaction checks), then billing, then a pharmacy counter, then a lab, then ABDM M1–M3, then multi-doctor, then IPD (nursing homes).
- **Second beachhead (month 9–15): Dubai and Sharjah polyclinics** (5–20 doctors, insured blue-collar volume). Lead with queue/ETA plus DHPO claims plus NABIDH. Sell through Indian-diaspora networks and TPAs.

### 5.3 Pricing hypothesis (to be tested)

| Tier | India (incl. GST) | UAE | KSA | Includes |
|---|---|---|---|---|
| **Free / Solo Starter** | ₹0, up to ~300 visits/month | — | — | Queue + ETA, WhatsApp booking (clinic-branded via Nabd's number), basic Rx |
| **Pro (per doctor)** | ₹999–1,499/month | AED 199–349 | SAR 249–399 | EMR, AI safety checks, own WA number, analytics, ABDM M1 |
| **Clinic (3–10 doctors)** | ₹2,500–4,000/month base + per doctor | AED 499 base + per practitioner | SAR 599 base + per practitioner | Reception, billing, claims (GCC), multi-doctor queue |
| **Pharmacy (standalone)** | ₹750–1,500/month | AED 249–399 | SAR 299–449 (+RSD) | Inventory, GST/VAT, H1/narcotics registers, Rx ingestion, refill reminders |
| **Lab** | ₹1,000–2,500/month | AED 299+ | SAR 349+ | Orders, LIS, reports on WA |
| **Hospital** | Per bed or per module (quote) | Quote | Quote | IPD, OT, NABH/ADHICS packs |
| **Pass-through** | WhatsApp at cost + 10–20% | same | same | Meta per-message fees |

These anchor just above Practo Ray and Eka, on the strength of ETA and AI value, and at or near Clinicea's AED 199 price point. **Test** a per-visit price (e.g. ₹1–2 per completed visit) against a per-doctor price for solo doctors, because TOKENN's ₹1/patient sets that reference point.

### 5.4 Channel strategy and WhatsApp Business costs

**Meta pricing model (official).** Per-message billing since 1 Jul 2025. Only delivered *templates* are charged, by category (marketing, utility, authentication). **Non-template replies within the 24-hour customer-service window are free, and utility templates sent within an open window are free.** Click-to-WhatsApp ad entry points open a 72-hour free window. Volume tiers apply to utility and authentication. INR billing must be migrated by 31 Dec 2026. SAR and AED billing were added 1 Apr 2026. Recent changes: India marketing up (1 Jan 2026); KSA marketing up (1 Apr 2026); Qatar utility/authentication up (1 Jul 2026); Kuwait and Oman got standalone, higher utility rates (1 Oct 2026) ([Meta pricing docs](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing)).

**Indicative rates per delivered message** *(secondary sources; verify against Meta's CSV rate card before budgeting)*:

| Market | Marketing | Utility | Authentication | Source |
|---|---|---|---|---|
| India | ₹0.8631 (~USD 0.0118) | ~₹0.115 (~USD 0.0014) | ~₹0.115 | [Monty Mobile](https://montymobile.com/blogs/whatsapp-business-api-pricing-in-india-inr-rates-gst-and-the-2026-currency-migration-deadline), [WhAutomate](https://whautomate.com/whatsapp-business-api-pricing-india); +18% GST |
| UAE | USD 0.0576 | USD 0.0157 | USD 0.0157 | [Chatmi](https://chatmi.io/en/blog/whatsapp-pricing-2026-guide) |
| KSA | USD 0.0576 | USD 0.0107 | USD 0.0107 | same |
| Qatar | USD 0.0341 | USD 0.0120 | USD 0.0120 | same |
| Egypt | USD 0.0644 | USD 0.0036 | USD 0.0036 | same |
| Kuwait | USD 0.0792 | USD 0.0440 | USD 0.0440 | same |
| Oman | USD 0.0341 | USD 0.0247 | USD 0.0247 | same |

**⚠ Conflict.** Some BSP blogs (AiSensy, Chatmi) claim service or free-form replies became chargeable from 1 Oct 2026, beyond a 1,000/month free allowance. Meta's developer page as fetched on 2026-10-17 still says non-template messages in the window are free. Treat this as **unverified** and model a downside scenario.

**Unit economics** *(estimate)*. A patient-initiated booking and check-in is free (service window). A reminder 18–24 hours later is one utility template (₹0.115 / USD 0.011–0.016). ETA updates on the day are usually inside a window the patient reopened, so free. A post-visit Rx/receipt is free if inside the window, otherwise one utility template.
- **India:** about ₹0.2–0.5 per visit. A 1,000-visit/month clinic costs about ₹200–500/month.
- **UAE:** about USD 20–30/month. **KSA:** about USD 15–20/month.

Avoid marketing templates for clinical comms. Classify reminders strictly as utility. **Policy constraint:** since 15 Jan 2026 the WhatsApp Business Solution Terms bar *general-purpose* AI assistants, but business-specific AI for customer service is allowed ([TechCrunch](https://techcrunch.com/2025/10/18/whatssapp-changes-its-terms-to-bar-general-purpose-chatbots-from-its-platform/?rand=324)). Nabd's bot must stay scoped to booking, queue and clinic FAQs, and must not drift into general medical Q&A.

**Number strategy.** Offer (a) the Nabd aggregator number (zero setup, for the free tier) and (b) the clinic's own number on the Cloud API (Pro and above). Meta's embedded-signup flow makes the second practical. Nabd becomes a Tech Provider or uses a BSP initially.

**Channels**
- **India:** field sales plus doctor-referral loops (each clinic's patients see "powered by Nabd"). IMA/IAP specialty-society city chapters. Pharma medical reps as distribution (many pharma companies sponsor clinic tools), but **avoid pharma-funded data deals**: they conflict with DPDP and neutrality. Partnering with local pharmacy distributors (stockists) can seed the pharmacy module.
- **UAE:** reseller or partner with a TPA or medical-billing company (they already touch every polyclinic), Indian business councils, and DHA/Arab Health events.
- **KSA:** partner with a local integrator holding NPHIES/ZATCA expertise (a ZATCA-certified e-invoicing provider; *candidates unverified*) and a local entity (a Saudi CR is needed for many contracts).

### 5.5 Partnerships

| Partner type | India | GCC | Purpose |
|---|---|---|---|
| Government rails | **ABDM** (sandbox → M1/M2/M3; DHIS incentives; Scan & Share), NHCX | DHA NABIDH, DoH Malaffi, MOHAP Riayati, CHI NPHIES, ZATCA | Certification = sales enabler |
| Drug knowledge | CIMS/MIMS India (licensed DB + DDI) | MIMS ME / First Databank / local codes (DDC, SFDA) | AI safety checks |
| Labs | Regional chains (e.g. Neuberg, Vijaya, Tenet; *candidates unverified*) | Unilabs, Medsol *(unverified)* | Order/result exchange; revenue share |
| Pharmacy | Distributors/stockists; independent pharmacies | Chains (Aster, Life, Nahdi, Al-Dawaa) for Rx fulfilment | Rx → fulfilment loop |
| Insurers / TPAs | Vidal (Bajaj), Medi Assist, MDIndia *(unverified)* | NAS, Nextcare, MedNet; Bupa/Tawuniya in KSA | OPD claims, cashless, distribution |
| Cloud | AWS/Azure India | UAE & KSA regions (G42/Core42, STC, Oracle, Azure, AWS, Google) | Residency |
| Payments | UPI (Razorpay/Cashfree) | Network International, HyperPay/Tap, Tabby (elective) | Prepaid bookings / no-show fees |

---

## 6. Key risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| 1 | **AI CDS classified as SaMD** (CDSCO draft Oct 2025; SFDA MDS-G010 covers CDS) | Medium | High | Non-autonomous, explainable "flag + source" design; clinician override logging; start an ISO 13485 / IEC 62304 track; pre-submission with regulators before GCC launch |
| 2 | **GCC certification treadmill** (NABIDH, Malaffi, Riayati, DHPO, Shafafiya, NPHIES, ZATCA, RSD) delays expansion | High | High | Sequence one emirate at a time; FHIR-core + adapter architecture; partner with certified integrators |
| 3 | **Residency architecture mistakes** (PHI leaks to a non-local region, incl. via LLM APIs or WhatsApp media) | Medium | Very high | Region-pinned tenants; in-region AI inference; never send PHI in WA templates beyond the minimum; legal review of Meta as a processor (WhatsApp Cloud API hosting is not in-country) **⚠** |
| 4 | **WhatsApp platform risk**: price rises (e.g. Kuwait/Oman Oct 2026), possible service-message charges, policy changes on AI bots | Medium | Medium | Keep templates utility-class; pass-through pricing; SMS/RCS/app fallback; per-market cost monitoring |
| 5 | **Low WTP in India**; "free" pharma-funded EMRs; Practo bundling after its IPO | High | Medium | Free solo tier → paid on ETA/AI value; per-visit pricing; upsell pharmacy/lab |
| 6 | **ETA accuracy fails** (doctors run late, emergencies, unrecorded breaks) | Medium | High | Doctor "running late" signal; conservative ETA bands; learn per doctor; display a confidence range |
| 7 | **DPDP compliance cliff (May 2027)** + consent-manager rules (Nov 2026); SDF designation | Medium | High | Build notices, consent, erasure and 72 h breach flows now; DPO; annual DPIA |
| 8 | **ABDM friction** (sandbox instability, callback bugs, policy churn, DHIS end-dates) | Medium | Medium | Server-side consent enforcement; budget 3–6 months for M1–M3 |
| 9 | **Cross-facility sharing legal exposure** (UAE requires authority-approved exceptions; KSA sensitive-data rules) | Medium | High | Within-country sharing only at launch; patient + doctor dual consent; time-boxed access; full audit; HIE-mediated where possible |
| 10 | **Incumbent response**: Practo, Eka or Clinicea add ETA; TOKENN moves upmarket | Medium | Medium | Speed; depth of the workflow (Rx + pharmacy + queue loop); network effects of global doctor profiles |
| 11 | **Regulatory flux in UAE PDPL / e-invoicing / KSA waves** | High | Low–Med | Quarterly reg-watch; configurable tax and retention engines |
| 12 | **Arabic/RTL and Hijri underestimated** | Medium | Medium | i18n and RTL from sprint 1; native-speaker QA |

---

## Appendix A: Items flagged for verification before build or launch

1. DPDP Rules: official Gazette text for R6–R8, R13 and the Fourth Schedule (rule numbering differs across secondary sources).
2. NABH DHS 2nd Edition: final vs draft status, and the HIS/EMR criteria.
3. DHIS extension: confirm the year of the "31 December" end date with NHA.
4. Current Practo Ray, Eka and HealthPlix pricing (vendor quotes); whether HealthPlix is pharma-funded.
5. UAE e-invoicing: Ministerial Decision 66/2026 and the provider-appointment deadline.
6. UAE PDPL Executive Regulations: publication status.
7. Qatar health-data residency and the breach-notification timeline.
8. KSA medical-record retention; Wasfaty obligations for private clinics; MDS-G27.
9. WhatsApp: Meta's official CSV rate cards for IN, AE, SA, QA, and the status of service-message charges after 1 Oct 2026.
10. Paras HMS, Medware/"Medas", Okadoc, MediBuddy and Docprime: current status.
11. Oman, Bahrain and Egypt VAT rates and healthcare treatment (background knowledge only).
