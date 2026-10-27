import { notFound } from "next/navigation";
import Gallery from "./Gallery";

export const dynamic = "force-dynamic";

// FEF-023: component gallery for the browser tests (synthetic data only). 404 unless NABD_UI_GALLERY=1 is set on the server.
export default function Page() {
  if (process.env.NABD_UI_GALLERY !== "1") notFound();
  return <Gallery />;
}
