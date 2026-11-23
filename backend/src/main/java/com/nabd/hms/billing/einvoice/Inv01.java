package com.nabd.hms.billing.einvoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FIN-011: the e-invoice payload (GST schema INV-01, version 1.1) of one B2B invoice, from the stored figures only. Pure.
 * Amounts are exact decimals with two places. No patient name or other PHI goes out: the buyer is identified by GSTIN.
 * shortcut: the invoice holds no buyer legal name or address and the clinic record no address/PIN in a form billing may read,
 * so SellerDtls/BuyerDtls carry GSTIN, name (seller) and state only; the IRP's mandatory address fields must be added to the
 * data model before a real GSP submission can be accepted (see the FIN-011 report).
 */
final class Inv01 {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    record Invoice(String number, LocalDate date, String sellerGstin, String sellerName, String buyerGstin, long roundOffMinor, long totalMinor,
                   List<Item> items) {
    }

    /** discountMinor is the invoice discount allocated to the line; taxableMinor is after it. */
    record Item(String description, String hsn, int quantity, long discountMinor, long taxableMinor, BigDecimal ratePercent,
                long igstMinor, long cgstMinor, long sgstMinor, long cessMinor) {
        long totalMinor() {
            return taxableMinor + igstMinor + cgstMinor + sgstMinor + cessMinor;
        }
    }

    private Inv01() {
    }

    private static BigDecimal money(long minor) {
        return BigDecimal.valueOf(minor, 2);
    }

    private static String state(String gstin) {
        return gstin != null && gstin.length() >= 2 ? gstin.substring(0, 2) : null;
    }

    static Map<String, Object> build(Invoice inv) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Version", "1.1");
        out.put("TranDtls", ordered("TaxSch", "GST", "SupTyp", "B2B", "RegRev", "N"));
        out.put("DocDtls", ordered("Typ", "INV", "No", inv.number(), "Dt", DAY.format(inv.date())));
        out.put("SellerDtls", ordered("Gstin", inv.sellerGstin(), "LglNm", inv.sellerName(), "Stcd", state(inv.sellerGstin())));
        out.put("BuyerDtls", ordered("Gstin", inv.buyerGstin(), "Pos", state(inv.buyerGstin()), "Stcd", state(inv.buyerGstin())));
        List<Map<String, Object>> items = new ArrayList<>();
        long assessable = 0, igst = 0, cgst = 0, sgst = 0, cess = 0;
        for (Item it : inv.items()) {
            long gross = it.taxableMinor() + it.discountMinor();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("SlNo", String.valueOf(items.size() + 1));
            m.put("PrdDesc", it.description());
            m.put("IsServc", it.hsn() == null || it.hsn().startsWith("99") ? "Y" : "N");
            m.put("HsnCd", it.hsn());
            m.put("Qty", it.quantity());
            m.put("UnitPrice", money(gross).divide(BigDecimal.valueOf(Math.max(1, it.quantity())), 3, RoundingMode.HALF_UP));
            m.put("TotAmt", money(gross));
            m.put("Discount", money(it.discountMinor()));
            m.put("AssAmt", money(it.taxableMinor()));
            m.put("GstRt", it.ratePercent());
            m.put("IgstAmt", money(it.igstMinor()));
            m.put("CgstAmt", money(it.cgstMinor()));
            m.put("SgstAmt", money(it.sgstMinor()));
            m.put("CesAmt", money(it.cessMinor()));
            m.put("TotItemVal", money(it.totalMinor()));
            items.add(m);
            assessable += it.taxableMinor();
            igst += it.igstMinor();
            cgst += it.cgstMinor();
            sgst += it.sgstMinor();
            cess += it.cessMinor();
        }
        out.put("ItemList", items);
        out.put("ValDtls", ordered("AssVal", money(assessable), "CgstVal", money(cgst), "SgstVal", money(sgst), "IgstVal", money(igst),
                "CesVal", money(cess), "RndOffAmt", money(inv.roundOffMinor()), "TotInvVal", money(inv.totalMinor())));
        return out;
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
