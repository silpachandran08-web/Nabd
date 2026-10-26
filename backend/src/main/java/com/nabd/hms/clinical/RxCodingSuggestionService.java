package com.nabd.hms.clinical;

import com.nabd.hms.common.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** CLN-021: proposes drug master codes for existing free-text prescription lines, signed ones included. It only writes
 * suggestions to a side table; a signed row is never changed (V51 would refuse it anyway). A doctor accepts a suggestion
 * by picking the drug on a new line. */
@Service
class RxCodingSuggestionService {

    private final RxCodingSuggestionRepository repo;
    private final DrugCoding coding;
    private final TenantContext tenantContext;

    RxCodingSuggestionService(RxCodingSuggestionRepository repo, DrugCoding coding, TenantContext tenantContext) {
        this.repo = repo;
        this.coding = coding;
        this.tenantContext = tenantContext;
    }

    /** One batch for the bound tenant; returns the number of suggestions written. */
    @Transactional
    int run(int batch) {
        UUID tenantId = tenantContext.current();
        int written = 0;
        for (RxCodingSuggestionRepository.Pending p : repo.pending(tenantId, batch)) {
            var s = coding.suggest(p.drugName());
            if (s.isEmpty() && p.brandName() != null) {
                s = coding.suggest(p.brandName());
            }
            if (s.isPresent()) {
                written += repo.insert(tenantId, p.itemId(), s.get().drugId(), s.get().confidence());
            }
        }
        return written;
    }
}
