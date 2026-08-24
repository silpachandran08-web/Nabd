package com.nabd.hms.auth.dto;

import java.util.List;

public record MfaConfirmResponse(List<String> recoveryCodes) {
}
