import { notFound } from "next/navigation";
import ChartGallery from "./ChartGallery";

export const dynamic = "force-dynamic";

// FEF-026: chart kit gallery (every primitive x the ten states, synthetic data only). It stands in for Storybook, which this
// repository does not have: the FEF-051 visual harness snapshots it in both themes. 404 unless NABD_UI_GALLERY=1 is set on the server.
export default function Page() {
  if (process.env.NABD_UI_GALLERY !== "1") notFound();
  return <ChartGallery />;
}
