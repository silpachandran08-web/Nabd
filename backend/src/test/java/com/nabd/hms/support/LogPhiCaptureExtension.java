package com.nabd.hms.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.nabd.hms.common.PhiJsonEncoder;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/** OPS-028 log-capture harness (registered on ApiTestBase): every test's log lines are encoded exactly as stdout gets
 * them and scanned; a line that still holds a seeded synthetic PHI marker or an unmasked email/+CC phone fails the test
 * and names the logger. Same marker/shapes as scripts/ci/log_phi_scan.py. */
public class LogPhiCaptureExtension implements BeforeEachCallback, AfterEachCallback {

    public static final Pattern LEAK = Pattern.compile(
            "SYNTH-PHI-[A-Za-z0-9]+|[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\\.[A-Za-z]{2,}|(?<![\\w*])\\+\\d{8,15}");
    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(LogPhiCaptureExtension.class);

    /** The encoded line of every event, paired with its logger name. */
    public static final class Capture extends AppenderBase<ILoggingEvent> {
        public List<String[]> linesForTest() {
            return lines;
        }

        final List<String[]> lines = new CopyOnWriteArrayList<>();
        private final PhiJsonEncoder encoder = new PhiJsonEncoder();

        @Override
        protected void append(ILoggingEvent e) {
            lines.add(new String[]{e.getLoggerName(), new String(encoder.encode(e), StandardCharsets.UTF_8)});
        }
    }

    /** "logger: match" for every leak found. */
    public static List<String> violations(List<String[]> lines) {
        List<String> out = new ArrayList<>();
        for (String[] l : lines) {
            var m = LEAK.matcher(l[1]);
            if (m.find()) {
                out.add(l[0] + ": " + m.group().replaceAll("(?<=.{3}).", "*"));
            }
        }
        return out;
    }

    @Override
    public void beforeEach(ExtensionContext ctx) {
        Capture c = new Capture();
        c.setContext(((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).getLoggerContext());
        c.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(c);
        ctx.getStore(NS).put("cap", c);
    }

    @Override
    public void afterEach(ExtensionContext ctx) {
        Capture c = ctx.getStore(NS).remove("cap", Capture.class);
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(c);
        c.stop();
        List<String> bad = violations(c.lines);
        if (!bad.isEmpty()) {
            throw new AssertionError("PHI marker or unmasked PHI in logs (" + bad.size() + "): " + bad.stream().distinct().limit(5).toList());
        }
    }
}
