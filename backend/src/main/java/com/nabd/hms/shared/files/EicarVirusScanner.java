package com.nabd.hms.shared.files;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** shortcut: finds only the EICAR test string; replace with the clamd adapter (same port) in a cell that runs ClamAV. */
@Component
class EicarVirusScanner implements VirusScanner {

    private static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    @Override
    public String engine() {
        return "eicar-signature";
    }

    @Override
    public boolean infected(byte[] data) {
        return new String(data, StandardCharsets.ISO_8859_1).contains(EICAR);
    }
}
