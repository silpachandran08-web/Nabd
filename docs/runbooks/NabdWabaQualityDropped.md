# NabdWabaQualityDropped (OPS-027, old NB-264)

Warning. Meta lowered the quality rating of a WhatsApp sender number (`phone_number_id` label; 2 = yellow, 1 = red). A red sender gets its messaging limit cut and can be restricted.

1. In the Meta Business Manager check the number's quality reason and the current messaging limit.
2. Pause non-essential template sends from that number (marketing-type first) until the rating recovers; keep appointment-critical messages flowing from a healthy number if the cell has one.
3. Look at the templates most recently sent from it and the opt-out and block rates (COM-001 opt-out registry); fix the template or the audience, do not just resend.
4. Note the rating and action in the ticket. The alert clears when the rating is back to green.

The `waba_quality_rating` gauge is a proposed Comms metric; until the emitter exists, check Meta directly.
