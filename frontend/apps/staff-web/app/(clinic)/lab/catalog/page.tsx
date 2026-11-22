import LabCatalogue from "../../../components/lab/LabCatalogue";
import PlannedScreen from "../../../shell/PlannedScreen";

// PHL-041: the lab test and panel catalogue. PlannedScreen keeps the nav's gate for a direct URL (lab:view and the clinic's
// lab module, else the no-access state and nothing is fetched); changing the catalogue also needs lab:catalogue.
export default function Page() {
  return <PlannedScreen id="lab-catalog"><LabCatalogue /></PlannedScreen>;
}
