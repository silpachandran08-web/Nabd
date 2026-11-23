package com.nabd.hms.pharmacy.dto;

import java.util.List;

/**
 * PHL-023: what the counter must do for the signed-in seller. pharmacistOnDuty is false when the clinic has no pharmacist in charge
 * or the caller holds no in-date pharmacist entry in the licence register. ackClasses: schedule classes whose line needs a second
 * confirmation with a reason (LineItemRequest.scheduleAckReason); pharmacistWarningClasses: classes whose sale shows the
 * "no registered pharmacist on duty" banner. Both lists are the market's pharmacy_schedule_rules, never decided in the browser.
 */
public record CounterRulesResponse(boolean pharmacistOnDuty, List<String> ackClasses, List<String> pharmacistWarningClasses) {
}
