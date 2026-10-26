package com.nabd.hms.common;

/**
 * OPS-015: the v1 domain event catalogue (docs/architecture/events.md). Each type has a JSON Schema for its data at
 * api/events/&lt;type&gt;.schema.json and a log-only {@link OutboxEventHandler} from {@link DomainEventHandlers} until a
 * real consumer (OPS-016 relay) takes over. Additive changes stay in v1; a breaking change is a new .v2 constant.
 */
public enum DomainEventType {
    APPOINTMENT_BOOKED("scheduling.appointment.booked.v1", "core/scheduling"),
    APPOINTMENT_CANCELLED("scheduling.appointment.cancelled.v1", "core/scheduling"),
    APPOINTMENT_RESCHEDULED("scheduling.appointment.rescheduled.v1", "core/scheduling"),
    APPOINTMENT_ARRIVED("scheduling.appointment.arrived.v1", "core/scheduling"),
    QUEUE_ENTRY_CREATED("visit.queue_entry.created.v1", "core/visit"),
    QUEUE_ENTRY_CHANGED("visit.queue_entry.changed.v1", "core/visit"),
    VISIT_COMPLETED("visit.completed.v1", "core/visit"),
    DOCTOR_DELAY_CHANGED("scheduling.doctor_delay.changed.v1", "core/scheduling"),
    DOCTOR_STATUS_CHANGED("scheduling.doctor_status.changed.v1", "core/scheduling"),
    RX_SIGNED("clinical.rx.signed.v1", "core/clinical"),
    ALLERGY_CHANGED("clinical.allergy.changed.v1", "core/clinical"),
    INVOICE_PAID("billing.invoice.paid.v1", "core/billing"),
    PAYMENT_RECORDED("billing.payment.recorded.v1", "core/billing"),
    CONSENT_CHANGED("consent.changed.v1", "core/patient"),
    ORGANIZATION_CONFIG_CHANGED("organization.config.changed.v1", "core/organization"),
    TENANT_REGION_PINNED("organization.tenant.region_pinned.v1", "core/organization"),
    ROLE_CHANGED("iam.role.changed.v1", "core/iam"),
    STAFF_CHANGED("iam.staff.changed.v1", "core/iam"),
    SESSION_REVOKED("iam.session.revoked.v1", "core/iam"),
    CATALOGUE_CHANGED("billing.catalogue.changed.v1", "core/billing"),
    NOTIFICATION_REQUESTED("comms.notification.requested.v1", "core/messaging"),
    NOTIFICATION_CANCELLED("comms.notification.cancelled.v1", "core/messaging"),
    INBOUND_RECEIVED("comms.inbound.received.v1", "core/messaging"),
    MESSAGE_STATUS_CHANGED("comms.message.status_changed.v1", "core/messaging"),
    ORGANIZATION_IMPORT_COMPLETED("organization.import.completed.v1", "core/organization"),
    DRUG_MASTER_VERSION_ACTIVATED("clinical.drug_master.version_activated.v1", "core/clinical"),
    PLATFORM_ENTITLEMENTS_CHANGED("platform.entitlements.changed.v1", "core/platform"),
    PRIVACY_NOTICE_PUBLISHED("organization.privacy_notice.published.v1", "core/organization"),
    BOOKING_ROUTED("scheduling.booking.routed.v1", "core/scheduling");

    private final String type;
    private final String source;

    DomainEventType(String type, String source) {
        this.type = type;
        this.source = source;
    }

    /** CloudEvents {@code type}, also the outbox event_type the dispatcher routes on. */
    public String type() {
        return type;
    }

    /** CloudEvents {@code source}. */
    public String source() {
        return source;
    }
}
