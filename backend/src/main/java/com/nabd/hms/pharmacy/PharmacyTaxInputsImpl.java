package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.pharmacy.api.PharmacyTaxInputs;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** PHL-021: pharmacy's side of the tax contract. No tax is computed here. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class PharmacyTaxInputsImpl implements PharmacyTaxInputs {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final PharmacyTaxInputsRepository repo;
    private final TenantContext tenant;

    PharmacyTaxInputsImpl(PharmacyTaxInputsRepository repo, TenantContext tenant) {
        this.repo = repo;
        this.tenant = tenant;
    }

    @Override
    public Map<UUID, Inputs> inputs(Collection<UUID> itemIds) {
        Map<UUID, Inputs> out = new HashMap<>();
        repo.inputs(tenant.current(), itemIds).forEach((id, r) -> out.put(id, new Inputs(r.priceInclusive(), r.zeroRated())));
        return out;
    }

    @Override
    public void requireWithinMrp(UUID itemId, BigDecimal unitPrice) {
        repo.mrp(tenant.current(), itemId).ifPresent(mrp -> {
            if (unitPrice.compareTo(mrp) > 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "price-above-mrp", "Price above MRP",
                        "The selling price cannot be higher than the MRP (" + mrp.toPlainString() + ").", Map.of("mrp", mrp));
            }
        });
    }

    @Override
    public List<String> licenceLines() {
        Map<String, String> m = new TreeMap<>();
        repo.licenceJson(tenant.current()).ifPresent(json -> {
            try {
                m.putAll(JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() { }));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // an unreadable licence blob prints no licence line rather than failing the invoice
            }
        });
        return m.entrySet().stream().filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .map(e -> "Drug licence " + e.getKey() + ": " + e.getValue()).toList();
    }
}
