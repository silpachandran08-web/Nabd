package com.nabd.hms.setup.dto;

import java.util.List;

public record ComplianceHealthResponse(Licences licences, List<Item> items) {
    public record Licences(int total, int expiringSoon, int expired) {
    }

    public record Item(String kind, String label, String status, String detail, String link) {
    }
}
