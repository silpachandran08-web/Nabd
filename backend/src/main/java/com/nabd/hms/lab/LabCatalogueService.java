package com.nabd.hms.lab;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.lab.LabDtos.ImportRequest;
import com.nabd.hms.lab.LabDtos.ImportResult;
import com.nabd.hms.lab.LabDtos.LabPanel;
import com.nabd.hms.lab.LabDtos.LabTest;
import com.nabd.hms.lab.LabDtos.PanelList;
import com.nabd.hms.lab.LabDtos.PanelRequest;
import com.nabd.hms.lab.LabDtos.PanelTest;
import com.nabd.hms.lab.LabDtos.TestPage;
import com.nabd.hms.lab.LabDtos.TestPatch;
import com.nabd.hms.lab.LabDtos.TestRequest;
import com.nabd.hms.lab.LabRepository.PanelRow;
import com.nabd.hms.lab.LabRepository.PanelTestRow;
import com.nabd.hms.lab.LabRepository.TestRow;
import com.nabd.hms.organization.api.ChargeCatalogue;
import com.nabd.hms.organization.api.ChargeCatalogue.Charge;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * PHL-041: the lab catalogue. Each test and each panel owns one price-list head (category Lab, code LAB-&lt;code&gt; /
 * LABP-&lt;code&gt;) created and changed through organization.api.ChargeCatalogue, so billing, the price change log and the
 * catalogue show one price. A panel is billed as its own single head; its tests are what the lab performs. Issued bills
 * keep their own line prices, so a price change applies from the next bill. The tenant is the one bound to the transaction.
 */
@Service
public class LabCatalogueService {

    static final String CATEGORY = "Lab";
    static final String TEST_PREFIX = "LAB-";
    static final String PANEL_PREFIX = "LABP-";

    private final LabRepository repo;
    private final ChargeCatalogue charges;
    private final TenantContext tenantContext;
    private final ActorDirectory actors;
    private final AuditService audit;
    private final EventPublisher events;

    LabCatalogueService(LabRepository repo, ChargeCatalogue charges, TenantContext tenantContext, ActorDirectory actors,
                        AuditService audit, EventPublisher events) {
        this.repo = repo;
        this.charges = charges;
        this.tenantContext = tenantContext;
        this.actors = actors;
        this.audit = audit;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public TestPage tests(String q, String cursor, int limit) {
        UUID tenantId = tenantContext.current();
        int n = Math.max(1, Math.min(limit, 200));
        String term = q == null ? "" : q.trim();
        if (term.length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Search with up to 80 characters.");
        }
        String like = term.isEmpty() ? null : "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String after = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                after = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Bad cursor.");
            }
        }
        List<TestRow> rows = repo.tests(tenantId, like, after, n + 1);
        String next = null;
        if (rows.size() > n) {
            rows = rows.subList(0, n);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(rows.get(n - 1).code().getBytes(StandardCharsets.UTF_8));
        }
        return new TestPage(views(rows), next);
    }

    @Transactional
    public LabTest createTest(UUID actorId, String ip, TestRequest req) {
        UUID tenantId = tenantContext.current();
        if (req.outsourced() != null) { // no partner-lab register exists yet, so the id cannot be checked against this clinic
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "partner-lab-unknown", "Unknown partner lab",
                    "Partner labs are not set up yet; save the test without one.");
        }
        String code = req.code().toUpperCase(Locale.ROOT);
        if (!repo.testCodesIn(tenantId, List.of(code)).isEmpty()) throw duplicate(code);
        UUID chargeId = charges.add(actorId, TEST_PREFIX + code, req.name().trim(), CATEGORY, req.price(), blankToNull(req.taxCode()), true);
        UUID id = insert(tenantId, new TestRow(null, code, req.name().trim(), req.loinc(), req.sampleType().trim(), req.container().trim(),
                req.volumeMl(), blankToNull(req.method()), req.unit().trim(), req.tatMinutes(), chargeId, true, null));
        events.publish(tenantId, DomainEventType.LAB_CATALOGUE_CHANGED, id, Map.of("testId", id, "status", "added"));
        record(tenantId, actorId, ip, "lab.test_created", "lab_test", id, null, Map.of("code", code, "price", req.price().toPlainString()));
        return views(repo.testsByIds(tenantId, List.of(id))).get(0);
    }

    @Transactional
    public LabTest updateTest(UUID actorId, String ip, UUID id, TestPatch p) {
        UUID tenantId = tenantContext.current();
        TestRow t = repo.testForUpdate(tenantId, id).orElseThrow(() -> ApiException.notFound("Lab test not found."));
        Charge c = charges.find(List.of(t.chargeItemId())).get(t.chargeItemId());
        TestRow n = new TestRow(t.id(), t.code(), pick(p.name(), t.name()), p.loinc() == null ? t.loinc() : p.loinc(),
                pick(p.sampleType(), t.sampleType()), pick(p.container(), t.container()), p.volumeMl() == null ? t.volumeMl() : p.volumeMl(),
                p.method() == null ? t.method() : blankToNull(p.method()), pick(p.unit(), t.unit()),
                p.tatMinutes() == null ? t.tatMinutes() : p.tatMinutes(), t.chargeItemId(), p.active() == null ? t.active() : p.active(), null);
        BigDecimal price = p.price() == null ? c.baseAmount() : p.price();
        String taxCode = p.taxCode() == null ? c.taxCode() : blankToNull(p.taxCode());
        repo.updateTest(tenantId, n);
        if (!n.name().equals(c.name()) || price.compareTo(c.baseAmount()) != 0 || !Objects.equals(taxCode, c.taxCode()) || n.active() != c.active()) {
            charges.update(actorId, c.id(), n.name(), price, taxCode, n.active()); // FIN-016: the price change log row is written there
        }
        events.publish(tenantId, DomainEventType.LAB_CATALOGUE_CHANGED, id, Map.of("testId", id, "status", "updated"));
        record(tenantId, actorId, ip, "lab.test_updated", "lab_test", id,
                Map.of("price", c.baseAmount().toPlainString(), "active", t.active(), "tatMinutes", t.tatMinutes()),
                Map.of("price", price.toPlainString(), "active", n.active(), "tatMinutes", n.tatMinutes()));
        return views(repo.testsByIds(tenantId, List.of(id))).get(0);
    }

    @Transactional(readOnly = true)
    public PanelList panels() {
        return new PanelList(panelViews(tenantContext.current()));
    }

    @Transactional
    public LabPanel createPanel(UUID actorId, String ip, PanelRequest req) {
        UUID tenantId = tenantContext.current();
        String code = req.code().toUpperCase(Locale.ROOT);
        if (new HashSet<>(req.testIds()).size() != req.testIds().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Each test appears once in a panel.");
        }
        if (repo.panelCodeExists(tenantId, code)) throw duplicate(code);
        List<TestRow> tests = repo.testsByIds(tenantId, req.testIds());
        if (tests.size() != req.testIds().size() || tests.stream().anyMatch(t -> !t.active())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-test", "Unknown test",
                    "A panel can only hold active tests of this clinic's catalogue.");
        }
        UUID chargeId = charges.add(actorId, PANEL_PREFIX + code, req.name().trim(), CATEGORY, req.price(), null, true);
        UUID id;
        try {
            id = repo.insertPanel(tenantId, code, req.name().trim(), chargeId, req.testIds());
        } catch (DuplicateKeyException e) { // added by another request since the check above
            throw duplicate(code);
        }
        events.publish(tenantId, DomainEventType.LAB_CATALOGUE_CHANGED, id, Map.of("panelId", id, "status", "added"));
        record(tenantId, actorId, ip, "lab.panel_created", "lab_panel", id, null,
                Map.of("code", code, "price", req.price().toPlainString(), "tests", tests.size()));
        return panelViews(tenantId).stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Add-only: a code the catalogue has, or whose price-list code is taken, is skipped and never overwritten. Imported tests
     * carry no price (0.00) and stay inactive, with an inactive price-list head, until the lab sets a price and activates them.
     */
    @Transactional
    public ImportResult importPack(UUID actorId, String ip, ImportRequest req) {
        UUID tenantId = tenantContext.current();
        LabStarterPack pack = LabStarterPack.load(req.pack()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "pack-not-found",
                "Starter pack not found", "There is no starter pack with that name."));
        List<String> codes = pack.lines().stream().map(LabStarterPack.Line::code).toList();
        Set<String> have = repo.testCodesIn(tenantId, codes);
        Set<String> taken = charges.taken(codes.stream().map(c -> TEST_PREFIX + c).toList());
        List<LabStarterPack.Line> add = pack.lines().stream().filter(l -> !have.contains(l.code()) && !taken.contains(TEST_PREFIX + l.code())).toList();
        List<String> toCreate = add.stream().map(LabStarterPack.Line::code).toList();
        List<String> skipped = codes.stream().filter(c -> !toCreate.contains(c)).toList();
        if (req.dryRun() || add.isEmpty()) {
            return new ImportResult(pack.name(), req.dryRun(), pack.reviewed(), pack.reviewNote(), toCreate, skipped, 0);
        }
        List<Map.Entry<UUID, Map<String, ?>>> evs = new ArrayList<>();
        for (LabStarterPack.Line l : add) {
            UUID chargeId = charges.add(actorId, TEST_PREFIX + l.code(), l.name(), CATEGORY, BigDecimal.ZERO, null, false);
            UUID id = insert(tenantId, new TestRow(null, l.code(), l.name(), l.loinc(), l.sampleType(), l.container(), null, null, l.unit(),
                    l.tatMinutes(), chargeId, false, null));
            evs.add(Map.entry(id, Map.of("testId", id, "status", "added")));
        }
        events.publishAll(tenantId, DomainEventType.LAB_CATALOGUE_CHANGED, evs);
        record(tenantId, actorId, ip, "lab.catalogue_imported", "lab_catalogue", null, null,
                Map.of("pack", pack.name(), "reviewed", pack.reviewed(), "created", add.size(), "skipped", skipped.size()));
        return new ImportResult(pack.name(), false, pack.reviewed(), pack.reviewNote(), toCreate, skipped, add.size());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    private UUID insert(UUID tenantId, TestRow t) {
        try {
            return repo.insertTest(tenantId, t);
        } catch (DuplicateKeyException e) { // added by another request since the caller's check
            throw duplicate(t.code());
        }
    }

    private List<LabTest> views(List<TestRow> rows) {
        Map<UUID, Charge> ch = charges.find(rows.stream().map(TestRow::chargeItemId).toList());
        return rows.stream().map(t -> {
            Charge c = ch.get(t.chargeItemId());
            return new LabTest(t.id(), t.code(), t.name(), t.loinc(), t.sampleType(), t.container(), t.volumeMl(), t.method(), t.unit(),
                    t.tatMinutes(), c.baseAmount(), c.taxCode(), c.id(), c.code(), t.active(), t.updatedAt());
        }).toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    private List<LabPanel> panelViews(UUID tenantId) {
        List<PanelRow> panels = repo.panels(tenantId);
        if (panels.isEmpty()) return List.of();
        List<PanelTestRow> links = repo.panelTests(tenantId);
        Map<UUID, TestRow> tests = new LinkedHashMap<>();
        repo.testsByIds(tenantId, links.stream().map(PanelTestRow::testId).distinct().toList()).forEach(t -> tests.put(t.id(), t));
        Set<UUID> chargeIds = new HashSet<>();
        panels.forEach(p -> chargeIds.add(p.chargeItemId()));
        tests.values().forEach(t -> chargeIds.add(t.chargeItemId()));
        Map<UUID, Charge> ch = charges.find(chargeIds);
        List<LabPanel> out = new ArrayList<>();
        for (PanelRow p : panels) {
            List<PanelTest> items = links.stream().filter(l -> l.panelId().equals(p.id())).map(l -> tests.get(l.testId()))
                    .map(t -> new PanelTest(t.id(), t.code(), t.name(), ch.get(t.chargeItemId()).baseAmount())).toList();
            BigDecimal total = items.stream().map(PanelTest::price).reduce(BigDecimal.ZERO, BigDecimal::add);
            Charge c = ch.get(p.chargeItemId());
            out.add(new LabPanel(p.id(), p.code(), p.name(), c.baseAmount(), total, total.subtract(c.baseAmount()).max(BigDecimal.ZERO),
                    c.id(), c.code(), p.active(), items));
        }
        return out;
    }

    private void record(UUID tenantId, UUID actorId, String ip, String action, String entity, UUID entityId, Object before, Object after) {
        String[] a = actors.findActor(tenantId, actorId).map(x -> new String[]{x.name(), x.role()}).orElse(new String[]{"Unknown", "Unknown"});
        audit.record(tenantId, "staff", actorId, a[0], a[1], ip, action, entity, entityId, before, after);
    }

    private static ApiException duplicate(String code) {
        return new ApiException(HttpStatus.CONFLICT, "duplicate-code", "Code exists", "The lab catalogue already has the code " + code + ".",
                Map.of("code", "DUPLICATE_CODE", "labCode", code));
    }

    private static String pick(String value, String old) {
        return value == null ? old : value.trim();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
