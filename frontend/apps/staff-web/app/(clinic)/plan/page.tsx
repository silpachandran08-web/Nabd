import PlannedScreen from "../../shell/PlannedScreen";
import PlanOptions from "../../components/plan/PlanOptions";

// FEF-058: owner.plan. Shows what the plan includes and, for each owner area the plan lacks, the upgrade option.
export default function PlanPage() {
  return (
    <PlannedScreen id="plan">
      <PlanOptions />
    </PlannedScreen>
  );
}
