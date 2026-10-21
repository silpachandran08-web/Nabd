package com.nabd.hms.organization.dpa;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** The versioned DPA text of one market: the word for the clinic's role depends on the law (fiduciary in India, controller in the Gulf). */
final class DpaText {

    private DpaText() {
    }

    static String version() {
        return props().getProperty("version");
    }

    static String publishedOn() {
        return props().getProperty("publishedOn");
    }

    static int reacceptDays() {
        return Integer.parseInt(props().getProperty("reacceptDays"));
    }

    /** Per-market wording lives in dpa.properties ({market}.law / .controller / .processor, else default.*). */
    static String controllerTerm(String market) {
        return market("controller", market);
    }

    static String processorTerm(String market) {
        return market("processor", market);
    }

    private static String market(String key, String market) {
        Properties p = props();
        return p.getProperty(market + "." + key, p.getProperty("default." + key));
    }

    /** Plain-text lines: "# " heading, "## " subheading, "- " bullet, else a paragraph. */
    static List<String> lines(String market, String clinicName) {
        String law = market("law", market);
        List<String> out = new ArrayList<>();
        for (String raw : read("dpa-v1.txt").split("\n")) {
            if (raw.contains("{{SUBPROCESSORS}}")) {
                read("sub-processors.txt").lines().filter(l -> !l.isBlank()).map(l -> l.split("\\|", 2))
                        .forEach(p -> out.add("- " + p[0] + ": " + p[1]));
                continue;
            }
            out.add(raw.replace("{{CLINIC}}", clinicName).replace("{{CONTROLLER}}", controllerTerm(market))
                    .replace("{{PROCESSOR}}", processorTerm(market)).replace("{{LAW}}", law));
        }
        return out;
    }

    static String html(List<String> lines) {
        StringBuilder h = new StringBuilder();
        boolean list = false;
        for (String l : lines) {
            boolean bullet = l.startsWith("- ");
            if (list && !bullet) {
                h.append("</ul>");
                list = false;
            }
            if (bullet && !list) {
                h.append("<ul>");
                list = true;
            }
            if (bullet) {
                h.append("<li>").append(esc(l.substring(2))).append("</li>");
            } else if (l.startsWith("## ")) {
                h.append("<h3>").append(esc(l.substring(3))).append("</h3>");
            } else if (l.startsWith("# ")) {
                h.append("<h2>").append(esc(l.substring(2))).append("</h2>");
            } else if (!l.isBlank()) {
                h.append("<p>").append(esc(l)).append("</p>");
            }
        }
        return h.append(list ? "</ul>" : "").toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static Properties props() {
        Properties p = new Properties();
        try (InputStream in = DpaText.class.getResourceAsStream("/dpa/dpa.properties")) {
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("dpa.properties missing", e);
        }
        return p;
    }

    private static String read(String name) {
        try (InputStream in = DpaText.class.getResourceAsStream("/dpa/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(name + " missing", e);
        }
    }
}
