package com.nabd.hms.queue.dto;

import java.util.List;

public record AppointmentPage(List<AppointmentResponse> data, PageMeta page) {
}
