package com.nabd.hms.staff.dto;

import java.util.List;

public record StaffPage(List<StaffResponse> data, PageMeta page) {
}
