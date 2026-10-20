package fx;

import com.nabd.hms.support.Hazard;

public class OtcGateApiTest {
    @Test
    @Hazard({"H-04"})
    void otcCheckoutRefusesRxItem() {}

    @Test
    @Hazard("H-04")
    void rxLineNeedsSignedPrescriptionItem(int n) {}

    @Test
    void untagged() {}
}
