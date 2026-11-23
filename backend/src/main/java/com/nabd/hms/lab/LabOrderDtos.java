package com.nabd.hms.lab;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PHL-043: lab orders. Prices are the price-list head's at order time (a snapshot on the order; the bill uses the head at checkout). */
public final class LabOrderDtos {

    private LabOrderDtos() {
    }

    /** Exactly one of testId and panelId. */
    @Schema(name = "LabOrderItemRequest")
    public record ItemRequest(UUID testId, UUID panelId) {
    }

    /** kind: inhouse (this clinic's lab), partner (a partner lab on Nabd, needs partnerLabId) or external (a lab outside Nabd: order sheet only, nothing charged). */
    @Schema(name = "LabOrderDestination")
    public record Destination(@NotNull @Pattern(regexp = "inhouse|partner|external") String kind, UUID partnerLabId) {
    }

    /**
     * queueEntryId: the visit the order is placed from (its checkout gets the charges); encounterId: the visit's first leg, when the
     * caller knows it. fastingHours: set when the patient must fast (1 to 24); the patient is then sent the fasting notice.
     */
    @Schema(name = "LabOrderRequest")
    public record OrderRequest(@NotNull UUID patientId, UUID encounterId, UUID queueEntryId,
                               @NotEmpty @Size(max = 30) List<@NotNull @Valid ItemRequest> items,
                               @NotNull @Pattern(regexp = "routine|urgent|stat") String priority,
                               @NotNull @Valid Destination destination,
                               @Size(max = 500) @Pattern(regexp = LabDtos.TEXT) String clinicalNote,
                               @Min(1) @Max(24) Integer fastingHours) {
    }

    @Schema(name = "LabOrderCharge")
    public record Charge(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String chargeCode,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal amount) {
    }

    /**
     * accessionHint: the order's printed reference (also the order sheet's barcode); sample accession numbers come with collection.
     * charges: what was put on the visit's checkout (empty for an external lab or an order without a visit).
     * fastingNotice: requested | blocked (no messaging consent) | template_missing (the clinic has no approved lab_fasting template);
     * null when the order needs no fasting.
     */
    @Schema(name = "LabOrderPlaced")
    public record OrderPlaced(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID orderId,
                              @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accessionHint,
                              @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Charge> charges,
                              String fastingNotice) {
    }

    @Schema(name = "LabOrderItem")
    public record OrderItem(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID testId,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
                            UUID panelId, String panelName,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal price) {
    }

    /** cancellable: nothing has been collected yet (status ordered). */
    @Schema(name = "LabOrder")
    public record Order(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accessionHint,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID patientId,
                        UUID queueEntryId, UUID orderingDoctorId,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String priority,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String destinationKind,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
                        String clinicalNote, Integer fastingHours,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant orderedAt,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal total,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean cancellable,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<OrderItem> items) {
    }

    /** STAT first, then urgent, then routine; oldest first inside a priority. */
    @Schema(name = "LabOrderList")
    public record OrderList(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Order> items) {
    }

    @Schema(name = "LabOrderCancelRequest")
    public record CancelRequest(@NotBlank @Size(max = 200) @Pattern(regexp = LabDtos.TEXT) String reason) {
    }

    /** chargesRemoved: taken off the visit's checkout; chargesAlreadyBilled: already on an invoice (they stay; a refund is the desk's decision). */
    @Schema(name = "LabOrderCancelled")
    public record Cancelled(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID orderId,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int chargesRemoved,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int chargesAlreadyBilled) {
    }

    /** kind: test | panel. orders: how many of the doctor's orders held it. */
    @Schema(name = "LabOrderFavourite")
    public record Favourite(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String kind,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal price,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int orders) {
    }

    @Schema(name = "LabOrderFavourites")
    public record Favourites(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Favourite> items) {
    }

    /** An order sheet for a lab outside Nabd, for a clinic without the lab module (no catalogue): the doctor types the tests. */
    @Schema(name = "ExternalLabSheetRequest")
    public record ExternalSheetRequest(@NotEmpty @Size(max = 30) List<@NotBlank @Size(max = 120) @Pattern(regexp = LabDtos.TEXT) String> tests,
                                       @Size(max = 120) @Pattern(regexp = LabDtos.TEXT) String labName,
                                       @Pattern(regexp = "routine|urgent|stat") String priority,
                                       @Size(max = 500) @Pattern(regexp = LabDtos.TEXT) String clinicalNote,
                                       @Min(1) @Max(24) Integer fastingHours) {
    }
}
