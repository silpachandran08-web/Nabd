package com.nabd.hms.queue.dto;

public record TransferResponse(QueueEntryResponse closedLeg, QueueEntryResponse newLeg) {
}
