import PlannedScreen from "../../shell/PlannedScreen";

// FEF-058: owner route skeleton. The owning squad (see shell/navRegistry.ts) fills it; until then it is the honest "not available yet" state.
export default function Page() {
  return <PlannedScreen id="hr" />;
}
