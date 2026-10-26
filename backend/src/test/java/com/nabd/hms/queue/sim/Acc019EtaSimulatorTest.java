package com.nabd.hms.queue.sim;

import com.nabd.hms.queue.sim.EtaSimulator.BaselinePredictor;
import com.nabd.hms.queue.sim.EtaSimulator.Params;
import com.nabd.hms.queue.sim.EtaSimulator.Prediction;
import com.nabd.hms.queue.sim.EtaSimulator.Summary;
import com.nabd.hms.queue.sim.EtaSimulator.Visit;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ACC-019 simulator: golden synthetic dataset (4 weeks), per-doctor MAE and P80 coverage under 5 minutes, summary artefact,
 * CI accuracy gate against src/test/resources/eta/reference-baseline.json, parameter sweep, dataset schema check.
 * Updating the baseline is a deliberate act: run with -Deta.writeBaseline=true after an engine change is accepted.
 */
class Acc019EtaSimulatorTest {

    static final Params REFERENCE = new Params(0.3, 5, 5);
    static final Path BASELINE = Path.of("src/test/resources/eta/reference-baseline.json");

    /** Seeded, so the same every run: 4 doctors, 20 weekdays, ~25 visits a day, single FIFO queue per doctor from 09:00. */
    static List<Visit> goldenDataset() {
        Random rnd = new Random(20260101L);
        List<Visit> out = new ArrayList<>();
        long base = 1_767_225_600L; // 2026-01-01T00:00Z
        int id = 0;
        for (int d = 0; d < 4; d++) {
            String doctor = String.format("d0c%013x", d + 1);
            double meanService = 420 + 90 * d; // 7 to 11.5 minutes
            for (int day = 0; day < 28; day++) {
                if (day % 7 >= 5) continue;
                long start = base + day * 86400L + 9 * 3600L, t = start, free = start;
                String date = LocalDate.of(2026, 1, 1).plusDays(day).toString();
                for (int v = 0; v < 25; v++) {
                    t += (long) (-Math.log(1 - rnd.nextDouble()) * 480); // arrivals ~ every 8 min
                    long called = Math.max(t, free);
                    free = called + (long) Math.max(120, meanService + rnd.nextGaussian() * 150);
                    out.add(new Visit(String.format("e%015x", ++id), doctor, date, t, called));
                }
            }
        }
        return out;
    }

    private static Summary run(List<Visit> data, Params p) {
        return EtaSimulator.replay(data, () -> new BaselinePredictor(p));
    }

    @Test
    void reportsMaeAndP80CoveragePerDoctorQuicklyAndWritesTheSummaryArtefact() throws Exception {
        long t0 = System.nanoTime();
        Summary s = run(goldenDataset(), REFERENCE);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMinutes(5));
        assertThat(s.perDoctor()).hasSize(4);
        s.perDoctor().values().forEach(st -> {
            assertThat(st.n()).isEqualTo(500);
            assertThat(st.maeMin()).isBetween(0.0, 60.0);
            assertThat(st.p80Coverage()).isBetween(0.0, 1.0);
        });
        assertThat(s.overall().n()).isEqualTo(2000);
        Path dir = Path.of("target/eta-accuracy");
        Files.createDirectories(dir);
        StringBuilder json = new StringBuilder("{\"overall\":" + stats(s.overall()) + ",\"perDoctor\":{");
        s.perDoctor().forEach((d, st) -> json.append(json.charAt(json.length() - 1) == '{' ? "" : ",").append('"').append(d).append("\":").append(stats(st)));
        Files.writeString(dir.resolve("summary.json"), json.append("}}").toString());
        assertThat(Files.readString(dir.resolve("summary.json"))).contains("p80Coverage");
    }

    private static String stats(EtaSimulator.Stats st) {
        return String.format(java.util.Locale.ROOT, "{\"n\":%d,\"maeMin\":%.3f,\"p80Coverage\":%.4f,\"within10\":%.4f}", st.n(), st.maeMin(), st.p80Coverage(), st.within10());
    }

    @Test
    void accuracyGateHoldsTheReferenceAndFailsWhenCoverageDropsByMoreThanThreePoints() throws Exception {
        double current = run(goldenDataset(), REFERENCE).overall().p80Coverage();
        if (Boolean.getBoolean("eta.writeBaseline")) {
            Files.writeString(BASELINE, String.format(java.util.Locale.ROOT, "{\"p80Coverage\":%.4f}%n", current));
        }
        String json = Files.readString(BASELINE);
        double reference = Double.parseDouble(json.replaceAll("(?s).*\"p80Coverage\":\\s*([0-9.]+).*", "$1"));
        assertThat(EtaSimulator.gatePasses(reference, current, 3.0)).as("engine regressed: %s vs reference %s", current, reference).isTrue();

        // an "engine change" that drops the spread and promises p80 = p50 loses far more than 3 points
        Summary worse = EtaSimulator.replay(goldenDataset(), () -> new BaselinePredictor(REFERENCE) {
            @Override
            public Prediction predict(int ahead) {
                Prediction p = super.predict(ahead);
                return new Prediction(p.p50(), p.p50());
            }
        });
        assertThat(EtaSimulator.gatePasses(reference, worse.overall().p80Coverage(), 3.0)).isFalse();
        // boundary: exactly 3 points down passes, 3.1 fails
        assertThat(EtaSimulator.gatePasses(0.80, 0.77, 3.0)).isTrue();
        assertThat(EtaSimulator.gatePasses(0.80, 0.769, 3.0)).isFalse();
    }

    @Test
    void parameterSweepCoversEveryCombination() {
        var out = EtaSimulator.sweep(goldenDataset(), new double[]{0.1, 0.3}, new double[]{1, 5, 20}, new int[]{1, 5});
        assertThat(out).hasSize(12);
        assertThat(out.values()).allSatisfy(s -> assertThat(s.overall().n()).isEqualTo(2000));
    }

    @Test
    void datasetSchemaRejectsNamesPhoneNumbersAndFreeText() {
        String ok = "e00000000000001,d0c0000000000001,2026-01-05,1767603600,1767604200";
        assertThat(EtaSimulator.validate(List.of(EtaSimulator.HEADER, ok))).hasSize(1);
        assertThatThrownBy(() -> EtaSimulator.validate(List.of("entry,doctor,day,checkin,called,patient_name", ok + ",Asha")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EtaSimulator.validate(List.of(EtaSimulator.HEADER, "Asha Rao,d0c0000000000001,2026-01-05,1767603600,1767604200")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EtaSimulator.validate(List.of(EtaSimulator.HEADER, "e00000000000001,+919876543210,2026-01-05,1767603600,1767604200")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EtaSimulator.validate(List.of(EtaSimulator.HEADER, "e00000000000001,d0c0000000000001,2026-01-05,1767603600,1767600000")))
                .hasMessageContaining("before check-in");
        // the golden dataset itself is clean
        List<String> lines = new ArrayList<>(List.of(EtaSimulator.HEADER));
        goldenDataset().forEach(v -> lines.add(String.join(",", v.entry(), v.doctor(), v.day(), "" + v.checkin(), "" + v.called())));
        assertThat(EtaSimulator.validate(lines)).hasSize(2000);
    }
}
