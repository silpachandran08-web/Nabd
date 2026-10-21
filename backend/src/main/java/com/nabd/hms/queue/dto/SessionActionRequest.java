package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.Pattern;

public record SessionActionRequest(@Pattern(regexp = "^[a-z][a-z0-9_]{0,39}$") String reasonCode) {
}
