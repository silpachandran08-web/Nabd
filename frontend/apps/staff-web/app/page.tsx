import HomeRedirect from "./HomeRedirect";

// FEF-029: the session lives in the browser (claims snapshot), so the redirect is decided there; a signed-in user never passes through /login.
export default function Index() {
  return <HomeRedirect />;
}
