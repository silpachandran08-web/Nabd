package com.nabd.hms.identity.abdm;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.identity.integration.AbdmGateway;
import com.nabd.hms.identity.api.AbdmVerification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * IDC-016 (DEC-33): ABDM is off per clinic until the owner enables it. This class is the only caller of {@link AbdmGateway}
 * and checks the clinic's switch before every HPR call; the one call allowed while the switch is still off is the HFR
 * check that the owner's own "enable" request triggers. No @Transactional here: the remote calls must not run inside one.
 */
@Service
class AbdmService implements AbdmVerification {

    private static final Pattern HFR = Pattern.compile("^[A-Za-z0-9_-]{3,40}$");

    private final AbdmGateway gateway;
    private final AbdmSettingsService store;
    private final Clock clock;

    AbdmService(AbdmGateway gateway, AbdmSettingsService store, Clock clock) {
        this.gateway = gateway;
        this.store = store;
        this.clock = clock;
    }

    AbdmSettingsService.Settings get() {
        return store.find().orElse(new AbdmSettingsService.Settings(false, null, null));
    }

    AbdmSettingsService.Settings put(UUID staffId, String ip, boolean enabled, String hfrId) {
        if (!enabled) { // switching off makes no ABDM call
            store.save(false, null, null, staffId, ip);
            return get();
        }
        String hfr = hfrId == null ? "" : hfrId.strip();
        if (!HFR.matcher(hfr).matches()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-hfr-id", "Invalid HFR id", "The facility id has 3 to 40 letters, digits, '-' or '_'.");
        }
        AbdmSettingsService.Settings cur = get();
        if (cur.enabled() && cur.hfrVerified() && hfr.equals(cur.hfrId())) {
            return cur; // repeat of the same request: nothing to verify again
        }
        if (!gateway.facilityExists(hfr)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "hfr-not-found", "Facility not found",
                    "This facility id is not in the Health Facility Registry.");
        }
        store.save(true, hfr, clock.instant(), staffId, ip);
        return get();
    }

    @Override
    public boolean hprIdExists(String hprId) {
        if (!get().enabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "abdm-disabled", "ABDM is off", "ABDM is not enabled for this clinic.");
        }
        return gateway.professionalExists(hprId);
    }
}
