package com.nabd.hms.billing;

import com.nabd.hms.common.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FIN-015/FIN-016 decision table (CC-06/09/11/12): same/different doctor, inside/outside window, category, urgent. */
class ConsultationTierTest {

    private static final BigDecimal BASE = new BigDecimal("500"), FEE = new BigDecimal("800"), FU = new BigDecimal("200"), EM = new BigDecimal("1000");
    private static final UUID DR = UUID.randomUUID();

    private static ConsultationTier.Context ctx(Integer days, int window, boolean urgent) {
        return new ConsultationTier.Context(DR, days, window, urgent);
    }

    @Test
    void blankOverrideFallsBackToBaseAndOverrideWins() {
        assertThat(ConsultationTier.pick("Consultation", BASE, null, FU, EM, ctx(null, 7, false), null).price()).isEqualByComparingTo("500");
        assertThat(ConsultationTier.pick("Consultation", BASE, FEE, FU, EM, ctx(null, 7, false), null).price()).isEqualByComparingTo("800");
    }

    @Test
    void windowBoundaryIsInclusive() {
        assertThat(ConsultationTier.pick("Consultation", BASE, null, FU, EM, ctx(6, 7, false), null).tier()).isEqualTo("follow_up");
        assertThat(ConsultationTier.pick("Consultation", BASE, null, FU, EM, ctx(7, 7, false), null).tier()).isEqualTo("follow_up");
        assertThat(ConsultationTier.pick("Consultation", BASE, null, FU, EM, ctx(8, 7, false), null).tier()).isEqualTo("normal");
        assertThat(ConsultationTier.pick("Consultation", BASE, null, FU, EM, ctx(8, 10, false), null).tier()).isEqualTo("follow_up");
        assertThat(ConsultationTier.pick("Consultation", BASE, null, FU, EM, ctx(0, 7, false), null).reason()).isEqualTo("Follow-up · last visit 0 days ago");
        assertThat(ConsultationTier.pick("Consultation", BASE, null, FU, EM, ctx(1, 7, false), null).reason()).isEqualTo("Follow-up · last visit 1 day ago");
    }

    @Test
    void noPriorVisitOrNoFollowUpAmountIsNormal() {
        assertThat(ConsultationTier.pick("Consultation", BASE, null, FU, EM, ctx(null, 7, false), null).tier()).isEqualTo("normal");
        assertThat(ConsultationTier.pick("Consultation", BASE, null, null, EM, ctx(2, 7, false), null).tier()).isEqualTo("normal");
    }

    @Test
    void aClientCannotForceAFollowUpItIsNotEligibleFor() {
        assertThat(ConsultationTier.pick("Consultation", BASE, null, FU, EM, ctx(9, 7, false), "follow_up").tier()).isEqualTo("normal");
    }

    @Test
    void urgentIsSuggestedButNeverAutomatic() {
        assertThat(ConsultationTier.suggest("Consultation", BASE, null, FU, EM, ctx(2, 7, true))).extracting("tier", "reason").containsExactly("emergency", "Urgent check-in");
        assertThat(ConsultationTier.suggest("Consultation", BASE, null, FU, null, ctx(2, 7, true)).tier()).as("no emergency price on the head").isEqualTo("follow_up");
        assertThat(ConsultationTier.pick("Consultation", BASE, null, FU, EM, ctx(null, 7, true), null).tier()).as("charged tier without a pick").isEqualTo("normal");
        assertThat(ConsultationTier.pick("Consultation", BASE, null, FU, EM, ctx(null, 7, true), "emergency").price()).isEqualByComparingTo("1000");
        assertThat(ConsultationTier.pick("Consultation", BASE, FEE, FU, EM, ctx(2, 7, true), "normal").price()).as("switching to normal keeps the doctor fee").isEqualByComparingTo("800");
        assertThatThrownBy(() -> ConsultationTier.pick("Consultation", BASE, null, FU, null, ctx(null, 7, true), "emergency")).isInstanceOf(ApiException.class);
    }

    @Test
    void otherCategoriesNeverGetTiersOrDoctorFees() {
        for (String cat : new String[]{"Procedure", "Lab", "Service", "Pharmacy", "consultation", ""}) {
            for (int days : new int[]{0, 3, 30}) {
                for (boolean urgent : new boolean[]{true, false}) {
                    var p = ConsultationTier.pick(cat, BASE, FEE, FU, EM, ctx(days, 7, urgent), null);
                    assertThat(p.tier()).isEqualTo("normal");
                    assertThat(p.price()).isEqualByComparingTo("500");
                    assertThat(ConsultationTier.suggest(cat, BASE, FEE, FU, EM, ctx(days, 7, urgent)).price()).isEqualByComparingTo("500");
                }
            }
        }
        assertThatThrownBy(() -> ConsultationTier.pick("Procedure", BASE, null, FU, EM, ctx(1, 7, false), "emergency")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ConsultationTier.pick("Consultation", BASE, null, FU, EM, ctx(1, 7, false), "bogus")).isInstanceOf(ApiException.class);
    }
}
