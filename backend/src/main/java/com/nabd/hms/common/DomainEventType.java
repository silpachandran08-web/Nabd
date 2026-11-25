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
    APPOINTMENT_NO_SHOW("scheduling.appointment.no_show.v1", "core/scheduling"),
    APPOINTMENT_ARRIVED("scheduling.appointment.arrived.v1", "core/scheduling"),
    QUEUE_ENTRY_CREATED("visit.queue_entry.created.v1", "core/visit"),
    QUEUE_ENTRY_CHANGED("visit.queue_entry.changed.v1", "core/visit"),
    VISIT_COMPLETED("visit.completed.v1", "core/visit"),
    DOCTOR_DELAY_CHANGED("scheduling.doctor_delay.changed.v1", "core/scheduling"),
    DOCTOR_STATUS_CHANGED("scheduling.doctor_status.changed.v1", "core/scheduling"),
    RX_SIGNED("clinical.rx.signed.v1", "core/clinical"),
    ALLERGY_CHANGED("clinical.allergy.changed.v1", "core/clinical"),
    INVOICE_PAID("billing.invoice.paid.v1", "core/billing"),
    /** PLT-035: the visit's bill was settled at its terminal billing stop (checkout). Consumer: queue.InvoiceSettledConsumer (visit status). */
    INVOICE_SETTLED("billing.invoice.settled.v1", "core/billing"),
    PAYMENT_RECORDED("billing.payment.recorded.v1", "core/billing"),
    REFUND_APPROVED("billing.refund.approved.v1", "core/billing"),
    CONSENT_CHANGED("consent.changed.v1", "core/patient"),
    ORGANIZATION_CONFIG_CHANGED("organization.config.changed.v1", "core/organization"),
    TENANT_REGION_PINNED("organization.tenant.region_pinned.v1", "core/organization"),
    /** ADM-020: every tenant lifecycle move (TenantLifecycleService). Consumers: comms-gateway, control-plane/subscriptions. */
    TENANT_STATUS_CHANGED("organization.tenant.status_changed.v1", "core/organization"),
    ORGANIZATION_BRANCH_CREATED("organization.branch.created.v1", "core/organization"),
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
    BOOKING_ROUTED("scheduling.booking.routed.v1", "core/scheduling"),
    SENDER_THROTTLED("comms.sender.throttled.v1", "core/messaging"),
    ATTACHMENT_SCANNED("shared.attachment.scanned.v1", "core/shared"),
    CALENDAR_CHANGED("organization.calendar.changed.v1", "core/organization"),
    PATIENT_LINK_OPENED("patient.link.opened.v1", "core/patient"),
    PHARMACY_STOCK_BATCH_CHANGED("pharmacy.stock.batch_changed.v1", "core/pharmacy"),
    DISCOUNT_APPROVAL_REQUESTED("billing.discount.approval_requested.v1", "core/billing"),
    DISCOUNT_APPROVAL_DECIDED("billing.discount.approval_decided.v1", "core/billing"),
    PHARMACY_STOCK_TAKE_POSTED("pharmacy.stock_take.posted.v1", "core/pharmacy"),
    PHARMACY_WORKLIST_ENTRY_CREATED("pharmacy.worklist.entry_created.v1", "core/pharmacy"),
    PHARMACY_WORKLIST_STATUS_CHANGED("pharmacy.worklist.status_changed.v1", "core/pharmacy"),
    PHARMACY_RETURN_ACCEPTED("pharmacy.return.accepted.v1", "core/pharmacy"),
    PATIENT_PROXY_CHANGED("patient.proxy.changed.v1", "core/patient"),
    PATIENT_GUARDIAN_HANDOVER_DUE("patient.guardian.handover_due.v1", "core/patient"),
    PATIENT_GUARDIAN_HANDOVER_COMPLETED("patient.guardian.handover_completed.v1", "core/patient"),
    ORGANIZATION_EXPORT_READY("organization.export.ready.v1", "core/organization"),
    TEMPLATE_STATUS_CHANGED("comms.template.status_changed.v1", "core/messaging"),
    APPOINTMENT_CONFIRMED("scheduling.appointment.confirmed.v1", "core/scheduling"),
    RECALL_CREATED("engagement.recall.created.v1", "core/engagement"),
    SPECIALTY_PROFILE_ASSIGNED("specialty.profile.assigned.v1", "core/specialty"),
    /** COM-025: a patient's notification preferences changed (desk PUT or the WhatsApp Settings menu). Ids and version only. */
    PATIENT_NOTIFICATION_PREFERENCES_CHANGED("patient.notification_preferences.changed.v1", "core/patient"),
    RX_REVOKED("clinical.prescription.revoked.v1", "core/clinical"),
    MESSAGING_SETTINGS_CHANGED("organization.messaging_settings.changed.v1", "core/organization"),
    BREAK_GLASS_REVIEWED("iam.break_glass.reviewed.v1", "core/iam"),
    /** COM-044: a package was sold and paid at the desk; core.billing (packages) requests the package card message. Ids only. */
    PACKAGE_SOLD("billing.package.sold.v1", "core/billing"),
    /** PHL-008: the clinic's daily stock-alert digest was handed to its one named recipient. Ids and counts only. */
    PHARMACY_STOCK_ALERTS_DIGEST_READY("pharmacy.stock.alerts_digest_ready.v1", "core/pharmacy"),
    SPECIALTY_PHOTO_ADDED("specialty.photo.added.v1", "core/specialty"),
    /** PHL-019: a counter (OTC) sale was invoiced and its stock issued. Ids, a count and the total only. */
    PHARMACY_OTC_SALE_COMPLETED("pharmacy.otc_sale.completed.v1", "core/pharmacy"),
    /** PHL-009: a batch was taken off sale (by hand, by the nightly expiry pass or by a return into an expired batch). Ids and a reason code only. */
    PHARMACY_BATCH_QUARANTINED("pharmacy.batch.quarantined.v1", "core/pharmacy"),
    /** COM-015: produced by services/comms-gateway once per clinic and closed day: message counts and cost by channel and category. No PHI. */
    USAGE_REPORTED("comms.usage.reported.v1", "core/messaging"),
    /** ADM-016: a leaving clinic's rows were erased and its deletion certificate issued. Ids, the erase time and totals only. */
    ORGANIZATION_OFFBOARDING_ERASED("organization.offboarding.erased.v1", "core/organization"),
    /** ADM-047: a report was exported (Excel, CSV or PDF) through the audited export path. Ids, format and row count only. */
    REPORTING_EXPORT_CREATED("reporting.export.created.v1", "core/reporting"),
    /** PHL-041: a lab test or panel of the clinic's catalogue was added or changed. Ids only. */
    LAB_CATALOGUE_CHANGED("lab.catalogue.changed.v1", "core/lab"),
    /** PLT-024: a patient of this clinic was linked to the same person's record at a sibling branch. Ids only. */
    PATIENT_BRANCH_LINK_CREATED("patient.branch_link.created.v1", "core/patient");

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
