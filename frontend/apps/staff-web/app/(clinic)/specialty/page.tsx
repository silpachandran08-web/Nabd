import PlannedScreen from "../../shell/PlannedScreen";
import SpecialtyWorkspace from "./SpecialtyWorkspace";

// CLN-042: the nav gate (clinical:edit + EMR module) stays in PlannedScreen; the screen itself follows the caller's profile.
export default function Page() {
  return <PlannedScreen id="specialty"><SpecialtyWorkspace /></PlannedScreen>;
}
