"use client";

// FEF-030 (+FEF-029): an error boundary for one panel of the consult workspace. A panel that throws while rendering shows
// "This panel failed" with a Retry; the patient banner, the note editor and every other panel keep working.
// The report carries the panel name, error class, route shape and build id only (see boundaryReport).
import { Component, type ReactNode } from "react";
import { send } from "../../../TelemetryReporter";
import { boundaryReport } from "../../../lib/telemetryScrub";
import { ErrorState } from "../../ui/StateViews";

type Props = { panel: string; children: ReactNode };
type State = { failed: boolean };

export default class PanelBoundary extends Component<Props, State> {
  state: State = { failed: false };
  static getDerivedStateFromError(): State { return { failed: true }; }
  componentDidCatch(error: Error) {
    send(boundaryReport(error, window.location.pathname, `consult${this.props.panel}`, process.env.NEXT_PUBLIC_RELEASE));
  }
  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div data-testid={`panel-error-${this.props.panel}`}>
        <ErrorState message="This panel failed" onRetry={() => this.setState({ failed: false })} />
      </div>
    );
  }
}
