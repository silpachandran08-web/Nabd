package fx;

import com.nabd.hms.support.Hazard;

/** fixture: class-level tag; the word class in a comment must not confuse the scanner */
@Hazard("H-01")
class FxClassTest {
    @Test
    void one() {}
}
