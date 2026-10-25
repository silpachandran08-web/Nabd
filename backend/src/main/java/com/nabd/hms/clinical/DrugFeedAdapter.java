package com.nabd.hms.clinical;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** CLN-019: the seam between the licensed KB feed (CLN-018; real format and delivery are Needs external action) and the
 * importer. An adapter turns one feed file into a header plus normalised product records (see {@link JsonLinesDrugFeedAdapter}
 * for the record shape); it streams, so a 200k-product feed never sits in memory. */
interface DrugFeedAdapter {

    record DrugClass(String id, String name) {
    }

    /** kind is 'full' (everything absent from the feed is retired) or 'delta' (only the listed products change). */
    record Header(String kb, String month, String region, String kind, List<DrugClass> classes) {
    }

    /** Reads only the header (first record). */
    Header header(Path file);

    /** Streams the products (header skipped) in file order; throws {@link FeedException} on the first invalid record. */
    void products(Path file, Consumer<Map<String, Object>> sink);

    /** A malformed feed. The message names the record number and the rule, never the record's values. */
    class FeedException extends RuntimeException {
        FeedException(String message) {
            super(message);
        }
    }
}
