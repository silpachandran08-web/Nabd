package com.nabd.hms.queue.sim;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ACC-019 (T-091): offline replay of recorded queue events through an {@link EtaPredictor}. Pure Java, no database, no network,
 * no Spring: it runs from a CSV on a laptop or in CI and never touches production.
 *
 * <p>Dataset (CSV, header {@link #HEADER}): entry and doctor are pseudonymous hex ids, day is the clinic date, checkin and called are
 * epoch seconds. Nothing else is accepted ({@link #validate}): no names, phone numbers or free text can ride along.
 *
 * <p>Per doctor and day the replay walks check-ins and calls in time order. At each check-in the predictor is asked for p50/p80
 * (seconds until called, given how many patients are waiting ahead); at each call the predictor learns. Actual = called - checkin.
 * MAE is |p50 - actual|; P80 coverage is the share of visits called at or before the p80 prediction; within10 is |p50 - actual| <= 10 min.
 * The predictor is the seam for the real EtaEngine (ACC-017): {@link BaselinePredictor} stands in until that library is shared.
 */
public final class EtaSimulator {

    public static final String HEADER = "entry,doctor,day,checkin,called";
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{8,32}");
    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern EPOCH = Pattern.compile("\\d{9,11}");

    public record Visit(String entry, String doctor, String day, long checkin, long called) {
    }

    public record Prediction(long p50, long p80) {
    }

    /** Learns from calls, predicts at check-in. One instance per doctor and day. */
    public interface EtaPredictor {
        Prediction predict(int waitingAhead);

        void observeCall(long calledAt, boolean someoneWasWaiting);
    }

    public interface PredictorFactory {
        EtaPredictor forDoctorDay();
    }

    public record Stats(int n, double maeMin, double p80Coverage, double within10) {
    }

    public record Summary(Stats overall, Map<String, Stats> perDoctor) {
    }

    /** Sweepable parameters: EWMA alpha, shrinkage k (pseudo-observations of the prior), rounding in minutes. */
    public record Params(double alpha, double k, int roundMin) {
    }

    private EtaSimulator() {
    }

    /** Rejects any row or cell outside the schema; returns the visits. */
    public static List<Visit> validate(List<String> lines) {
        if (lines.isEmpty() || !HEADER.equals(lines.get(0).trim())) {
            throw new IllegalArgumentException("dataset header must be exactly: " + HEADER);
        }
        List<Visit> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            String[] c = lines.get(i).split(",", -1);
            if (c.length != 5 || !HEX.matcher(c[0]).matches() || !HEX.matcher(c[1]).matches() || !DAY.matcher(c[2]).matches()
                    || !EPOCH.matcher(c[3]).matches() || !EPOCH.matcher(c[4]).matches()) {
                throw new IllegalArgumentException("dataset row " + i + " does not match the schema");
            }
            long in = Long.parseLong(c[3]), called = Long.parseLong(c[4]);
            if (called < in) throw new IllegalArgumentException("dataset row " + i + ": called before check-in");
            out.add(new Visit(c[0], c[1], c[2], in, called));
        }
        return out;
    }

    public static List<Visit> load(Path csv) {
        try {
            return validate(Files.readAllLines(csv));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Summary replay(List<Visit> visits, PredictorFactory factory) {
        record Key(String doctor, String day) {
        }
        Map<Key, List<Visit>> byDay = new HashMap<>();
        for (Visit v : visits) byDay.computeIfAbsent(new Key(v.doctor(), v.day()), k -> new ArrayList<>()).add(v);
        Map<String, List<double[]>> results = new HashMap<>(); // doctor -> [absErr sec, hitP80, within10]
        for (var e : byDay.entrySet()) {
            List<Visit> day = e.getValue();
            // events in time order; a check-in at the same second as a call is processed first
            record Ev(long at, boolean call, Visit v) {
            }
            List<Ev> evs = new ArrayList<>();
            for (Visit v : day) {
                evs.add(new Ev(v.checkin(), false, v));
                evs.add(new Ev(v.called(), true, v));
            }
            evs.sort(Comparator.comparingLong(Ev::at).thenComparing(Ev::call));
            EtaPredictor p = factory.forDoctorDay();
            int waiting = 0;
            Map<String, Prediction> made = new HashMap<>();
            for (Ev ev : evs) {
                if (ev.call()) {
                    waiting--;
                    Prediction pr = made.get(ev.v().entry());
                    long actual = ev.v().called() - ev.v().checkin();
                    results.computeIfAbsent(ev.v().doctor(), d -> new ArrayList<>()).add(new double[]{
                            Math.abs(pr.p50() - actual), actual <= pr.p80() ? 1 : 0, Math.abs(pr.p50() - actual) <= 600 ? 1 : 0});
                    p.observeCall(ev.at(), waiting > 0);
                } else {
                    made.put(ev.v().entry(), p.predict(waiting));
                    waiting++;
                }
            }
        }
        Map<String, Stats> per = new java.util.TreeMap<>();
        List<double[]> all = new ArrayList<>();
        results.forEach((d, rows) -> {
            per.put(d, stats(rows));
            all.addAll(rows);
        });
        return new Summary(stats(all), per);
    }

    private static Stats stats(List<double[]> rows) {
        int n = rows.size();
        if (n == 0) return new Stats(0, 0, 1, 1);
        return new Stats(n, rows.stream().mapToDouble(r -> r[0]).sum() / n / 60.0,
                rows.stream().mapToDouble(r -> r[1]).sum() / n, rows.stream().mapToDouble(r -> r[2]).sum() / n);
    }

    /** One replay per parameter combination, for choosing alpha, k and rounding. */
    public static Map<Params, Summary> sweep(List<Visit> visits, double[] alphas, double[] ks, int[] roundings) {
        Map<Params, Summary> out = new java.util.LinkedHashMap<>();
        for (double a : alphas) for (double k : ks) for (int r : roundings) {
            Params p = new Params(a, k, r);
            out.put(p, replay(visits, () -> new BaselinePredictor(p)));
        }
        return out;
    }

    /** CI accuracy gate: fails when P80 coverage fell by more than maxDropPoints percentage points against the reference. */
    public static boolean gatePasses(double referenceCoverage, double currentCoverage, double maxDropPoints) {
        return (referenceCoverage - currentCoverage) * 100.0 <= maxDropPoints + 1e-9;
    }

    /**
     * Stand-in for the ACC-017 engine: the wait is (patients ahead) x the doctor's call-to-call gap, an EWMA shrunk towards a prior
     * with k pseudo-observations; p80 adds a spread from the EWMA absolute deviation. Replace by the shared EtaEngine without
     * changing the simulator.
     */
    public static class BaselinePredictor implements EtaPredictor {
        static final double PRIOR_GAP_S = 600; // operational default before a doctor has history, not clinical content
        private final Params params;
        private double gap = Double.NaN, dev = Double.NaN;
        private int obs = 0;
        private long lastCall = -1;

        public BaselinePredictor(Params params) {
            this.params = params;
        }

        private double shrunkGap() {
            return Double.isNaN(gap) ? PRIOR_GAP_S : (obs * gap + params.k() * PRIOR_GAP_S) / (obs + params.k());
        }

        private double shrunkDev() {
            return Double.isNaN(dev) ? PRIOR_GAP_S / 2 : (obs * dev + params.k() * PRIOR_GAP_S / 2) / (obs + params.k());
        }

        @Override
        public Prediction predict(int waitingAhead) {
            double p50 = waitingAhead * shrunkGap();
            double p80 = p50 + 1.28 * Math.sqrt(Math.max(waitingAhead, 1)) * shrunkDev();
            return new Prediction(round(p50, false), round(p80, true));
        }

        private long round(double s, boolean up) {
            double step = Math.max(1, params.roundMin()) * 60.0; // never promise earlier than the p80: round it up
            return (long) (up ? Math.ceil(s / step) * step : Math.round(s / step) * step);
        }

        @Override
        public void observeCall(long calledAt, boolean someoneWasWaiting) {
            if (lastCall >= 0 && someoneWasWaiting) {
                double g = calledAt - lastCall;
                gap = Double.isNaN(gap) ? g : params.alpha() * g + (1 - params.alpha()) * gap;
                double d = Math.abs(g - gap);
                dev = Double.isNaN(dev) ? d : params.alpha() * d + (1 - params.alpha()) * dev;
                obs++;
            }
            lastCall = calledAt;
        }
    }

    /** CLI: java ... EtaSimulator dataset.csv  -> prints one line per doctor and the overall figures (sweep via the API). */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: EtaSimulator <dataset.csv>");
            System.exit(2);
        }
        Summary s = replay(load(Path.of(args[0])), () -> new BaselinePredictor(new Params(0.3, 5, 5)));
        s.perDoctor().forEach((d, st) -> System.out.printf("doctor %s n=%d maeMin=%.2f p80Coverage=%.4f within10=%.4f%n", d, st.n(), st.maeMin(), st.p80Coverage(), st.within10()));
        System.out.printf("overall n=%d maeMin=%.2f p80Coverage=%.4f within10=%.4f%n", s.overall().n(), s.overall().maeMin(), s.overall().p80Coverage(), s.overall().within10());
    }
}
