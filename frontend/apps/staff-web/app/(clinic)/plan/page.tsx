import PlannedScreen from "../../shell/PlannedScreen";
import PlanOptions from "../../components/plan/PlanOptions";
import PlanAccountPanel from "../../components/plan/PlanAccountPanel";
import MessageUsagePanel from "../../components/plan/MessageUsagePanel";

// FEF-058: owner.plan. Shows what the plan includes and, for each owner area the plan lacks, the upgrade option.
// ADM-037: plan, usage, invoices and the self-service plan change above it.
// COM-015: message counts and cost per month against the plan allowance.
export default function PlanPage() {
  return (
    <PlannedScreen id="plan">
      <PlanOptions />
      <PlanAccountPanel />
      <MessageUsagePanel />
    </PlannedScreen>
  );
}
