package com.nabd.hms.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.iam.api.StaffLicenceSync;
import com.nabd.hms.organization.api.PlanAccount;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * COM-015: the owner's message usage. Counts and cost come from the Comms Gateway, which owns the messages and the provider price
 * list; the allowance is plan data ({@link PlanAccount#whatsappMessageAllowance}), so a plan without one shows cost only (either
 * DEC-35 answer renders). The allowance is measured against WhatsApp messages of the calendar month.
 * When the gateway reports a closed day ({@code comms.usage.reported.v1}) and the month's count has reached 80% or 100% of the
 * allowance, the owner gets one alert per threshold and month: a message_usage_alerts row (shown on the plan page) and, when the
 * clinic has an approved message_usage_alert template, one WhatsApp message per owner. No allowance = no alert.
 * shortcut: WhatsApp messages Core still sends in-process (clinics not yet cut over to the gateway, COM-008) are not in the gateway's
 * usage and so not counted here; they join when the clinic is cut over.
 */
@Service
public class MessageUsageService {

    static final int[] THRESHOLDS = {80, 100};

    /** The gateway's answer (GET /internal/v1/usage). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GatewayMonth(String month, List<Item> channels, long whatsappCount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(name = "MessageUsageItem")
    public record Item(String channel, String category, long count, long costMinor, String currency) {}

    @Schema(name = "MessageUsageAllowance")
    public record Allowance(long limit, long used) {}

    @Schema(name = "MessageUsageAlert")
    public record Alert(int thresholdPct, Instant at) {}

    /** allowance is absent when the plan has none configured. */
    @Schema(name = "MessageUsage")
    public record Usage(String month, List<Item> channels, @JsonInclude(JsonInclude.Include.NON_NULL) Allowance allowance, List<Alert> alerts) {}

    private final CommsSenderClient gateway;
    private final PlanAccount plans;
    private final MessageUsageAlerts alerts;
    private final NotificationPort notifications;
    private final StaffLicenceSync staff;
    private final ClinicClock clock;

    MessageUsageService(CommsSenderClient gateway, PlanAccount plans, MessageUsageAlerts alerts, NotificationPort notifications, StaffLicenceSync staff, ClinicClock clock) {
        this.gateway = gateway;
        this.plans = plans;
        this.alerts = alerts;
        this.notifications = notifications;
        this.staff = staff;
        this.clock = clock;
    }

    /** Not transactional on purpose: the gateway call must not hold a database connection. */
    public Usage usage(UUID tenantId, String monthParam) {
        YearMonth month;
        try {
            month = monthParam == null || monthParam.isBlank() ? YearMonth.from(clock.today(tenantId)) : YearMonth.parse(monthParam);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-month", "Invalid month", "Month must be in ISO-8601 format such as 2026-07");
        }
        GatewayMonth g = gateway.usage(tenantId, month);
        OptionalLong allowance = plans.whatsappMessageAllowance(tenantId);
        return new Usage(month.toString(), g == null || g.channels() == null ? List.of() : g.channels(),
                allowance.isPresent() ? new Allowance(allowance.getAsLong(), g == null ? 0 : g.whatsappCount()) : null, alerts.of(month.atDay(1)));
    }

    /** Runs in the consumer's tenant transaction. Safe to repeat: the alert row's primary key lets each threshold through once a month. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReported(UUID tenantId, LocalDate day, long whatsappMonthCount) {
        OptionalLong allowance = plans.whatsappMessageAllowance(tenantId);
        if (allowance.isEmpty()) {
            return;
        }
        long limit = allowance.getAsLong();
        int crossed = 0;
        for (int pct : THRESHOLDS) {
            if (whatsappMonthCount * 100 >= limit * pct && alerts.raise(tenantId, day.withDayOfMonth(1), pct, whatsappMonthCount, limit)) {
                crossed = pct; // 80 and 100 on the same day: one message, for the higher
            }
        }
        if (crossed == 0 || !notifications.sendable(NotificationPurpose.MESSAGE_USAGE_ALERT, "en")) {
            return; // no approved template: the in-app alert stands, the WhatsApp copy is skipped
        }
        for (StaffLicenceSync.Contact owner : staff.owners()) {
            if (owner.phone() != null && !owner.phone().isBlank()) {
                notifications.request(new NotificationRequest(NotificationPurpose.MESSAGE_USAGE_ALERT, "message_usage", tenantId, null, owner.phone(), "en",
                        List.of(String.valueOf(crossed), String.valueOf(whatsappMonthCount), String.valueOf(limit)), null, null, null,
                        "message-usage:" + YearMonth.from(day) + ":" + crossed + ":" + owner.staffId()));
            }
        }
    }
}
