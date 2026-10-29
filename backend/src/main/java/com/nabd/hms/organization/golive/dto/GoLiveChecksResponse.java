package com.nabd.hms.organization.golive.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record GoLiveChecksResponse(List<Blocker> blockers, List<NiceToHave> niceToHave) {

    @Schema(name = "GoLiveBlocker")
    public record Blocker(String code, boolean ok, String fixUrl) {
    }

    @Schema(name = "GoLiveNiceToHave")
    public record NiceToHave(String code, boolean ok, String note) {
    }
}
