import RouteChrome from "./RouteChrome";

// FEF-029: the frame of the groups that have no clinic nav or top bar (auth, owner, platform): same skip link, title and content box the clinic shell uses.
export default function PlainFrame({ children }: { children: React.ReactNode }) {
  return (
    <div className="appShell">
      <RouteChrome />
      <div className="appShellContent"><div className="appShellBody" id="app-body">{children}</div></div>
    </div>
  );
}
