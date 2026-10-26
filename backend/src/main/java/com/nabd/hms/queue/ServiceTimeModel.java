package com.nabd.hms.queue;

/** ACC-016: the pure consult-time model. Durations are log-normal; all figures are seconds. No I/O, no clock. */
final class ServiceTimeModel {

    static final double ALPHA = 0.1;
    static final int SHRINK_K = 20;
    static final double CAP_FACTOR = 4.0;
    static final double Z80 = 0.8416212335729143;
    static final double DEFAULT_SIGMA = 0.4;

    private ServiceTimeModel() {
    }

    record Ewma(double mu, double var) {
    }

    /** Distribution of one consult: median seconds and log-sigma. */
    record Dist(double medianS, double sigma) {
        double quantile(double z) {
            return medianS * Math.exp(sigma * z);
        }

        double p80S() {
            return quantile(Z80);
        }

        double meanS() {
            return medianS * Math.exp(sigma * sigma / 2);
        }

        double varianceS() {
            return (Math.exp(sigma * sigma) - 1) * Math.exp(2 * Math.log(medianS) + sigma * sigma);
        }

        static Dist fromMedianP80(double median, double p80) {
            double s = p80 > median ? Math.log(p80 / median) / Z80 : DEFAULT_SIGMA;
            return new Dist(median, s);
        }
    }

    /** EWMA of the log duration and of its variance (West's incremental form). */
    static Ewma ewma(double mu, double var, double logDuration) {
        double d = logDuration - mu;
        return new Ewma(mu + ALPHA * d, (1 - ALPHA) * (var + ALPHA * d * d));
    }

    /** A consult longer than 4x the median is learned as 4x the median. */
    static double cap(double seconds, double medianS) {
        return Math.min(seconds, CAP_FACTOR * medianS);
    }

    static boolean isOverCap(double seconds, double medianS) {
        return seconds > CAP_FACTOR * medianS;
    }

    /** Empirical Bayes: weight n/(n+20) on the doctor's own figure, the rest on the prior. */
    static double blend(int n, double own, double prior) {
        double w = n / (double) (n + SHRINK_K);
        return w * own + (1 - w) * prior;
    }

    static Dist blend(int n, Dist own, Dist prior) {
        return new Dist(blend(n, own.medianS(), prior.medianS()), blend(n, own.sigma(), prior.sigma()));
    }

    /** E[D - t | D > t] in seconds (never below 60 s: a running consult is not about to end this instant). */
    static double remaining(Dist d, double elapsedS) {
        if (elapsedS <= 0) {
            return d.meanS();
        }
        double m = Math.log(d.medianS()), s = d.sigma(), lt = Math.log(elapsedS);
        double tail = 1 - phi((lt - m) / s);
        if (tail < 1e-9) {
            return 60;
        }
        double meanGiven = Math.exp(m + s * s / 2) * phi((m + s * s - lt) / s) / tail;
        return Math.max(60, meanGiven - elapsedS);
    }

    /** Standard normal CDF (Abramowitz-Stegun 7.1.26, error < 1.5e-7). */
    static double phi(double x) {
        double t = 1 / (1 + 0.3275911 * Math.abs(x) / Math.sqrt(2));
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
                * Math.exp(-x * x / 2);
        return x >= 0 ? 0.5 * (1 + y) : 0.5 * (1 - y);
    }
}
