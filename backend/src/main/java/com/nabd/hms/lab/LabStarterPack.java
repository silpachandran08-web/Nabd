package com.nabd.hms.lab;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PHL-041: a starter pack of common tests, reference data in resources/lab/packs/&lt;NAME&gt;.csv
 * (code,name,loinc,sample_type,container,unit,tat_minutes). Lines starting with # are notes; "# reviewed: true" marks a pack a
 * lab reviewer has signed off and "# review-note: ..." says what is still open. No prices and no reference ranges: the clinic sets them.
 */
record LabStarterPack(String name, boolean reviewed, String reviewNote, List<Line> lines) {

    record Line(String code, String name, String loinc, String sampleType, String container, String unit, int tatMinutes) {
    }

    /** name is validated by the request (^[A-Z][A-Z0-9_]{1,30}$), so it cannot leave the packs folder. */
    static Optional<LabStarterPack> load(String name) {
        try (InputStream in = LabStarterPack.class.getResourceAsStream("/lab/packs/" + name + ".csv")) {
            if (in == null) return Optional.empty();
            boolean reviewed = false;
            String note = null;
            List<Line> lines = new ArrayList<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String l = r.readLine(); l != null; l = r.readLine()) {
                l = l.trim();
                if (l.startsWith("# reviewed:")) reviewed = "true".equals(l.substring(11).trim());
                else if (l.startsWith("# review-note:")) note = l.substring(14).trim();
                if (l.isEmpty() || l.startsWith("#") || l.startsWith("code,")) continue;
                String[] c = l.split(",", -1);
                if (c.length != 7) throw new IllegalStateException("bad line in lab pack " + name + ": " + l);
                lines.add(new Line(c[0], c[1], c[2], c[3], c[4], c[5], Integer.parseInt(c[6])));
            }
            return Optional.of(new LabStarterPack(name, reviewed, note, List.copyOf(lines)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
