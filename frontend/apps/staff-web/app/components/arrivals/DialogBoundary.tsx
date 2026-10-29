"use client";

import { Component, type ReactNode } from "react";
import { useTranslations } from "next-intl";
import ArrivalsErrorPanel from "./ArrivalsErrorPanel";

function DialogFailPanel({ onRecover }: { onRecover: () => void }) {
  const t = useTranslations("arrivals.page");
  return <ArrivalsErrorPanel title={t("dialogFail")} message={t("dialogFailHint")} actionLabel={t("dialogFailAction")} onRecover={onRecover} />;
}

// A render error inside any Arrivals dialog (walk-in, vitals, ...) stops here: the board behind it keeps working
// and the receptionist gets a recover action that closes the dialog. Anything outside the dialogs reaches error.tsx.
export default class DialogBoundary extends Component<{ onRecover: () => void; resetKey: unknown; children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch(error: unknown) { console.error("arrivals dialog failed", error); }
  componentDidUpdate(prev: { resetKey: unknown }) { if (this.state.failed && prev.resetKey !== this.props.resetKey) this.setState({ failed: false }); } // another dialog was asked for
  recover = () => { this.setState({ failed: false }); this.props.onRecover(); };
  render() {
    if (!this.state.failed) return this.props.children;
    return <DialogFailPanel onRecover={this.recover} />;
  }
}
