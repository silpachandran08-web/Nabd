import PlannedScreen from "../shell/PlannedScreen";
import ExceptionsRail from "../components/exceptions/ExceptionsRail";
import SelfCheckInScreen from "../components/selfcheckin/SelfCheckInScreen";

// ACC-033 (reception.checkin): the door poster, its settings, recent self check-ins and the exceptions the desk can confirm.
export default function Page() {
  return (
    <PlannedScreen id="checkin">
      <SelfCheckInScreen><ExceptionsRail /></SelfCheckInScreen>
    </PlannedScreen>
  );
}
