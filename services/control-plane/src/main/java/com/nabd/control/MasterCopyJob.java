package com.nabd.control;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Locale;

/**
 * The cut-over job: a one-off run of the same image, never an HTTP endpoint, so only someone who can start it with both
 * databases' credentials can copy. Does nothing unless control-plane.copy.mode is set. Run it with the owning role of this
 * database (CONTROL_DB_USER) and a read-only, RLS-exempt role of Core's:
 *   CONTROL_COPY_MODE=verify CONTROL_COPY_SOURCE_URL=... CONTROL_COPY_SOURCE_USER=... CONTROL_COPY_SOURCE_PASSWORD=... \
 *     java -jar app.jar --spring.main.web-application-type=none
 * Exit code 0 = every object matches, 1 = a mismatch or a refusal (the reason is logged; row data never is).
 */
@Component
class MasterCopyJob implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MasterCopyJob.class);

    private final ControlPlaneProperties props;
    private final DataSource target;
    private final ConfigurableApplicationContext context;

    MasterCopyJob(ControlPlaneProperties props, DataSource target, ConfigurableApplicationContext context) {
        this.props = props;
        this.target = target;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        ControlPlaneProperties.Copy copy = props.copy();
        if (copy.mode().isBlank()) {
            return;
        }
        int code = execute(copy) ? 0 : 1;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    boolean execute(ControlPlaneProperties.Copy copy) {
        try (Connection source = DriverManager.getConnection(copy.sourceUrl(), copy.sourceUser(), copy.sourcePassword());
             Connection dst = target.getConnection()) {
            List<MasterCopy.Result> results = MasterCopy.run(MasterCopy.Mode.valueOf(copy.mode().toUpperCase(Locale.ROOT)), source, dst);
            log.info("master {}:\n{}", copy.mode(), MasterCopy.describe(results));
            return results.stream().allMatch(MasterCopy.Result::matches);
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.error("master {} refused: {}", copy.mode(), e.getMessage()); // schema-level reasons only
            return false;
        } catch (Exception e) {
            // a database error text can quote a row ("Key (email)=(...)"): log the class and SQLSTATE, never the message
            log.error("master {} failed: {} {}", copy.mode(), e.getClass(), e instanceof java.sql.SQLException q ? q.getSQLState() : "");
            return false;
        }
    }
}
