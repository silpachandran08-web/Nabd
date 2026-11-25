package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PLT-061: the boot guard for after the Control Plane cut-over. Off by default; on, Core refuses to start while its role can change master.*. */
class Plt061MasterWriteSelfCheckTest extends ApiTestBase {

    @Autowired ApplicationContext context;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(JdbcTemplate.class, () -> jdbc).withUserConfiguration(MasterWriteSelfCheck.class);

    @Test
    void offByDefaultSoCoreBootsExactlyAsBefore() {
        assertThat(context.getBeansOfType(MasterWriteSelfCheck.class)).as("the shared test context runs with default configuration").isEmpty();
        runner.run(c -> assertThat(c).doesNotHaveBean(MasterWriteSelfCheck.class));
        runner.withPropertyValues("app.master-cutover-check.enabled=false").run(c -> assertThat(c).doesNotHaveBean(MasterWriteSelfCheck.class));
        runner.withPropertyValues("app.master-cutover-check.enabled=true").run(c -> assertThat(c).hasSingleBean(MasterWriteSelfCheck.class));
    }

    @Test
    void switchedOnBeforeTheCutOverItRefusesToStartAndNamesWhatTheAppRoleCanStillWrite() {
        // today's truth: nabd_app writes master.* (the platform package lives in Core)
        assertThatThrownBy(() -> new MasterWriteSelfCheck(jdbc).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to start")
                .hasMessageContaining("master.subscriptions (as nabd_app)")
                .hasMessageContaining("claim_due_plan_changes");
    }

    @Test
    void aRoleWithoutMasterWritesPassesAndTheDatabaseRefusesItsDml() {
        String role = "plt061_cut_" + UUID.randomUUID().toString().substring(0, 8);
        su.execute("CREATE ROLE " + role + " LOGIN PASSWORD 'plt061' NOSUPERUSER NOBYPASSRLS");
        try {
            // after the cut-over (and before the schema is dropped) Core's role may at most read master during the dual-read window
            su.execute("GRANT USAGE ON SCHEMA master TO " + role);
            su.execute("GRANT SELECT ON ALL TABLES IN SCHEMA master TO " + role);
            JdbcTemplate cut = new JdbcTemplate(dataSourceFor(role, "plt061"));
            assertThat(MasterWriteSelfCheck.violations(cut)).isEmpty();
            new MasterWriteSelfCheck(cut).run(null); // boots

            for (String dml : List.of(
                    "INSERT INTO master.plans (code, name, monthly_price_cents, currency, seat_limit) VALUES ('plt061', 'x', 1, 'INR', 1)",
                    "UPDATE master.plans SET name = name",
                    "DELETE FROM master.subscriptions",
                    "TRUNCATE master.trial_signups")) {
                assertThatThrownBy(() -> cut.execute(dml)).as(dml).rootCause().hasMessageContaining("permission denied");
            }

            // one remaining write grant is enough to refuse the boot
            su.execute("GRANT UPDATE ON master.subscriptions TO " + role);
            assertThat(MasterWriteSelfCheck.violations(cut)).singleElement().asString().contains("master.subscriptions (as " + role + ")");
            su.execute("REVOKE UPDATE ON master.subscriptions FROM " + role);

            // a role it could SET ROLE to counts, even without inheriting it
            su.execute("GRANT nabd_control TO " + role + " WITH INHERIT FALSE, SET TRUE");
            assertThat(MasterWriteSelfCheck.violations(cut)).singleElement().asString().contains("(as nabd_control)");
            su.execute("REVOKE nabd_control FROM " + role);

            // and so does a SECURITY DEFINER function that writes master for it
            su.execute("GRANT EXECUTE ON FUNCTION claim_due_plan_changes(timestamptz) TO " + role);
            assertThat(MasterWriteSelfCheck.violations(cut)).singleElement().asString().contains("claim_due_plan_changes");
        } finally {
            su.execute("DROP OWNED BY " + role);
            su.execute("DROP ROLE " + role);
        }
    }
}
