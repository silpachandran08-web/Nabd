package com.nabd.hms.reports.dto;

import java.math.BigDecimal;

public record DailyMoneyResponse(BigDecimal billedToday, BigDecimal collectedToday, BigDecimal outstandingTotal,
                                  int invoiceCountToday, int paymentCountToday) {
}
