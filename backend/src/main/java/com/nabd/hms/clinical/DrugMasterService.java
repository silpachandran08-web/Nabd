package com.nabd.hms.clinical;

import com.nabd.hms.clinical.api.DrugMasterLookup;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** CLN-019: reads of the regional drug master for the caller's tenant region. Retired products stay resolvable by id
 * (existing prescriptions) and are never returned by search. */
@Service
public class DrugMasterService implements DrugMasterLookup {

    @Schema(name = "DrugIngredient")
    public record IngredientView(String id, String name, BigDecimal amount, String unit) {
    }

    @Schema(name = "DrugClass")
    public record ClassView(String id, String name) {
    }

    @Schema(name = "DrugSource")
    public record SourceView(String kb, String month) {
    }

    @Schema(name = "DrugDetail")
    public record DetailView(String id, String brand, List<String> generic, String strength, String form, String route,
                             List<String> schedule, String pregnancyCategory, String lactationNote, String status,
                             List<IngredientView> ingredients, List<ClassView> classes, SourceView source) {
    }

    @Schema(name = "DrugSummary")
    public record SummaryView(String id, String brand, String strength, String form, List<String> generic, List<String> schedule) {
    }

    private final DrugMasterRepository repo;
    private final PrescriptionRepository prescriptions;
    private final TenantContext tenantContext;

    DrugMasterService(DrugMasterRepository repo, PrescriptionRepository prescriptions, TenantContext tenantContext) {
        this.repo = repo;
        this.prescriptions = prescriptions;
        this.tenantContext = tenantContext;
    }

    @Transactional
    DetailView detail(String drugId) {
        DrugMasterRepository.Drug d = repo.find(region(), drugId).orElseThrow(DrugMasterService::notFound);
        List<DrugMasterRepository.Ingredient> ing = repo.ingredients(d.id());
        return new DetailView(d.id(), d.brand(), ing.stream().map(DrugMasterRepository.Ingredient::name).toList(), d.strength(), d.form(),
                d.route(), d.schedule(), d.pregnancyCategory(), d.lactationNote(), d.status(),
                ing.stream().map(i -> new IngredientView(i.id(), i.name(), i.amount(), i.unit())).toList(),
                repo.classes(d.id()).stream().map(c -> new ClassView(c.id(), c.name())).toList(),
                new SourceView(d.kb(), d.month()));
    }

    @Transactional
    List<SummaryView> search(String q, int limit) {
        String ts = DrugDisplay.tsQuery(q);
        if (ts.isEmpty()) {
            return List.of();
        }
        return repo.search(region(), ts, limit).stream()
                .map(s -> new SummaryView(s.id(), s.brand(), s.strength(), s.form(), s.generic(), s.schedule())).toList();
    }

    @Override
    @Transactional
    public Optional<DrugRef> find(String drugId) {
        return repo.find(region(), drugId).map(d -> new DrugRef(d.id(), d.brand(), !d.schedule().isEmpty(), "active".equals(d.status())));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    String region() {
        return prescriptions.findTenantRegion(tenantContext.current()).map(String::trim).orElse("IN");
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "DRUG_NOT_FOUND", "Drug not found", "No such drug in the drug master.");
    }
}
