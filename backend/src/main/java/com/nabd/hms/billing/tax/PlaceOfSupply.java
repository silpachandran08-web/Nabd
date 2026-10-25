package com.nabd.hms.billing.tax;

import java.util.regex.Pattern;

/** FIN-009: the inter/intra-state decision. A GSTIN's first two digits are the state code. */
public final class PlaceOfSupply {

    private static final Pattern GSTIN = Pattern.compile("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]");

    private PlaceOfSupply() {
    }

    public static boolean validGstin(String gstin) {
        return gstin != null && GSTIN.matcher(gstin).matches();
    }

    /** Services: place of supply is the clinic's state (never inter-state). Goods sold to a B2B buyer GSTIN in another state: IGST. */
    public static boolean interState(String clinicGstin, String buyerGstin, boolean goods) {
        return goods && validGstin(clinicGstin) && validGstin(buyerGstin)
                && !clinicGstin.substring(0, 2).equals(buyerGstin.substring(0, 2));
    }
}
