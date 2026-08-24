package com.nabd.hms.queue.dto;

public record WaitEstimateResponse(int estimatedMinutes, int patientsAhead, double avgVisitMinutes, boolean basedOnHistory) {
}
