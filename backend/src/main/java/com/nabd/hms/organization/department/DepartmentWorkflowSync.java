package com.nabd.hms.organization.department;

import com.nabd.hms.common.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.organization.department.DepartmentModels.DepartmentRow;

/**
 * ACC-013: the Solo preset points every department at the clinic_walkin template with the vitals_enabled toggle set from the
 * booking policy, so resolveStatusSequence drops (or restores) the vitals stage. Only that one toggle is ever written on a
 * department that already uses clinic_walkin; visits, queue entries and billing are never touched.
 */
@Component
public class DepartmentWorkflowSync {

    private static final String TEMPLATE = "clinic_walkin";

    /** What changes for one department: from (template, vitals) to (clinic_walkin, vitals). */
    public record Change(UUID departmentId, String departmentName, String fromTemplate, Boolean fromVitalsEnabled, boolean toVitalsEnabled) {
    }

    private final DepartmentRepository repo;
    private final TenantContext tenantContext;
    private final ObjectMapper objectMapper;

    DepartmentWorkflowSync(DepartmentRepository repo, TenantContext tenantContext, ObjectMapper objectMapper) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.objectMapper = objectMapper;
    }

    /** The changes {@link #apply} would make for the bound tenant, without making them. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Change> preview(boolean vitalsEnabled) {
        UUID tenantId = tenantContext.current();
        List<Change> out = new ArrayList<>();
        for (DepartmentRow d : repo.list(tenantId)) {
            var sel = repo.findSelection(tenantId, d.id());
            String template = sel.map(DepartmentModels.WorkflowSelectionRow::templateCode).orElse(null);
            Boolean current = sel.map(s -> toggles(s.togglesJson()).get("vitals_enabled")).orElse(null);
            boolean effective = current == null || current; // no toggle = the template default, vitals on
            if (!TEMPLATE.equals(template) || effective != vitalsEnabled) {
                out.add(new Change(d.id(), d.name(), template, sel.isEmpty() ? null : effective, vitalsEnabled));
            }
        }
        return out;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Change> apply(boolean vitalsEnabled) {
        UUID tenantId = tenantContext.current();
        List<Change> changes = preview(vitalsEnabled);
        if (changes.isEmpty()) {
            return changes;
        }
        UUID templateId = repo.findPlatformTemplate(TEMPLATE).orElseThrow().id();
        for (Change c : changes) {
            repo.upsertSelection(tenantId, c.departmentId(), templateId, "{\"vitals_enabled\":" + vitalsEnabled + "}");
        }
        return changes;
    }

    private Map<String, Boolean> toggles(String json) {
        try {
            return objectMapper.readValue(json, new tools.jackson.core.type.TypeReference<Map<String, Boolean>>() {
            });
        } catch (JacksonException e) {
            throw new IllegalStateException("malformed toggles JSON", e);
        }
    }
}
