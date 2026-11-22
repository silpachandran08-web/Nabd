package com.nabd.hms.organization.dataimport;

import com.nabd.hms.clinical.api.ImportedHistory;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.dataimport.ImportMapper.Drug;
import com.nabd.hms.organization.dataimport.ImportMapper.Parsed;
import com.nabd.hms.organization.dataimport.ImportMapper.Stock;
import com.nabd.hms.organization.dataimport.ImportMapper.Visit;
import com.nabd.hms.organization.dataimport.ImportRepository.Job;
import com.nabd.hms.patient.api.PatientDirectory;
import com.nabd.hms.pharmacy.api.ItemImport;
import com.nabd.hms.pharmacy.api.StockCommands;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ADM-013: the row rules of the visit-history, drug-list and opening-stock imports: what a dry run says about a row
 * ({@link #check}) and what a commit does with it ({@link #apply}). Nothing here writes another module's table: visits
 * go through clinical.api, items and stock through pharmacy.api. Every row is validated in full BEFORE a command is
 * called, because a command that throws would roll the whole chunk back.
 * shortcut: one or two lookups per row (fine for the 2,000-line stock list and a few ten thousand visits); batch the
 * lookups per chunk, as the patient import does, if a 100,000-row history file is too slow.
 */
@Component
class ImportRows {

    /** outcome: ready / duplicate / error from a dry run; created / skipped / error from a commit. */
    record Verdict(String outcome, String errorCode) {
    }

    /** One dry run or one commit chunk over a job's rows. seen = what earlier rows of the file already claimed. */
    record Pass(String type, Map<String, Integer> idx, String dateFormat, String region, LocalDate today, boolean batches, Set<String> seen) {
    }

    private final PatientDirectory patients;
    private final ImportedHistory history;
    private final ItemImport items;
    private final StockCommands stock;
    private final ClinicClock clock;
    private final TenantContext tenant;

    ImportRows(PatientDirectory patients, ImportedHistory history, ItemImport items, StockCommands stock, ClinicClock clock, TenantContext tenant) {
        this.patients = patients;
        this.history = history;
        this.items = items;
        this.stock = stock;
        this.clock = clock;
        this.tenant = tenant;
    }

    Pass pass(String type, Map<String, Integer> idx, String dateFormat) {
        UUID t = tenant.current();
        return new Pass(type, idx, dateFormat, clock.region(t), clock.today(t), "stock".equals(type) && items.mode().batches(), new HashSet<>());
    }

    boolean pharmacyItems() {
        return items.mode().items();
    }

    Verdict check(Pass p, String[] cells) {
        switch (p.type()) {
            case "visit_history" -> {
                Parsed<Visit> v = ImportMapper.visit(cells, p.idx(), p.dateFormat(), p.region(), p.today());
                if (v.value() == null) return error(v.errorCode());
                Optional<UUID> patient = patient(v.value());
                if (patient.isEmpty()) return error("PATIENT_NOT_FOUND");
                ImportedHistory.Visit doc = doc(v.value());
                if (!p.seen().add(patient.get() + "|" + doc)) return dup("DUP_FILE");
                return history.exists(patient.get(), doc) ? dup("DUP_REGISTRY") : ready();
            }
            case "drugs" -> {
                Parsed<Drug> d = ImportMapper.drug(cells, p.idx(), true);
                if (d.value() == null) return error(d.errorCode());
                if (!p.seen().add(d.value().name().toLowerCase(Locale.ROOT))) return dup("DUP_FILE");
                return items.findByName(d.value().name()).isPresent() ? dup("DUP_REGISTRY") : ready();
            }
            default -> {
                Parsed<Stock> s = ImportMapper.stock(cells, p.idx(), p.dateFormat(), p.today(), p.batches());
                if (s.value() == null) return error(s.errorCode());
                Stock st = s.value();
                if (!p.seen().add(st.item().name().toLowerCase(Locale.ROOT) + "|" + st.batchNo() + "|" + st.expiry())) return dup("DUP_FILE");
                // shortcut: a Hybrid pharmacy keeps one implicit batch per item, so the same list committed twice cannot be told apart there
                boolean have = items.findByName(st.item().name()).map(id -> items.hasBatch(id, st.batchNo(), st.expiry())).orElse(false);
                return have ? dup("DUP_REGISTRY") : ready();
            }
        }
    }

    /** Commits one 'ready' row. The registry checks run again: the clinic may have changed since the dry run, and a retried chunk must not double anything. */
    Verdict apply(Pass p, String[] cells, Job job) {
        switch (p.type()) {
            case "visit_history" -> {
                Parsed<Visit> v = ImportMapper.visit(cells, p.idx(), p.dateFormat(), p.region(), p.today());
                if (v.value() == null) return error(v.errorCode());
                Optional<UUID> patient = patient(v.value());
                if (patient.isEmpty()) return error("PATIENT_NOT_FOUND");
                ImportedHistory.Visit doc = doc(v.value());
                if (history.exists(patient.get(), doc)) return new Verdict("skipped", "DUP_REGISTRY");
                history.add(patient.get(), doc, job.source() == null ? SourcePresets.GENERIC : job.source(), job.id(), job.requestedBy());
                return new Verdict("created", null);
            }
            case "drugs" -> {
                Parsed<Drug> d = ImportMapper.drug(cells, p.idx(), true);
                if (d.value() == null) return error(d.errorCode());
                if (items.findByName(d.value().name()).isPresent()) return new Verdict("skipped", "DUP_REGISTRY");
                items.create(item(d.value()), job.requestedBy());
                return new Verdict("created", null);
            }
            default -> {
                Parsed<Stock> s = ImportMapper.stock(cells, p.idx(), p.dateFormat(), p.today(), p.batches());
                if (s.value() == null) return error(s.errorCode()); // e.g. BATCH_EXPIRED: the batch expired between the dry run and the commit
                Stock st = s.value();
                UUID item = items.findByName(st.item().name()).orElseGet(() -> items.create(item(st.item()), job.requestedBy()));
                if (items.hasBatch(item, st.batchNo(), st.expiry())) return new Verdict("skipped", "DUP_REGISTRY");
                stock.receiveBatch(item, new StockCommands.BatchReceipt(st.batchNo(), st.expiry(), st.item().mrp(), st.purchaseRate(), st.qty(), null, null, null),
                        job.requestedBy(), "Opening stock (import)");
                return new Verdict("created", null);
            }
        }
    }

    private Optional<UUID> patient(Visit v) {
        return v.oldRecordNo() != null ? patients.findByLegacyRecordNo(v.oldRecordNo()) : patients.findByPhoneDob(v.phone(), v.dob());
    }

    private static ImportedHistory.Visit doc(Visit v) {
        return new ImportedHistory.Visit(v.visitDate(), v.doctorName(), v.complaint(), v.diagnosis(), v.notes(), v.medications(), v.billNo(), v.billAmount());
    }

    private static ItemImport.NewItem item(Drug d) {
        return new ItemImport.NewItem(d.name(), d.genericName(), d.price(), d.mrp(), d.taxRate(), d.hsnCode(), d.packSize(), d.unit(), d.rx());
    }

    private static Verdict ready() {
        return new Verdict("ready", null);
    }

    private static Verdict dup(String code) {
        return new Verdict("duplicate", code);
    }

    private static Verdict error(String code) {
        return new Verdict("error", code);
    }
}
