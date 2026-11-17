import PlannedScreen from "../../shell/PlannedScreen";
import ChannelPolicy from "../../components/integrations/ChannelPolicy";

// FEF-058: owner route skeleton (the connection cards come with their squad, see shell/navRegistry.ts).
// COM-016: the WhatsApp channel exclusivity policy is shown here in plain words.
export default function Page() {
  return (
    <PlannedScreen id="integrations">
      <ChannelPolicy />
    </PlannedScreen>
  );
}
