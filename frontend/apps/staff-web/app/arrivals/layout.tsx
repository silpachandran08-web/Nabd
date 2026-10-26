import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import AccessGate from "../components/ui/AccessGate";

// FEF-028: per-route title (the page is a client component, so metadata lives here). FEF-038: from the catalogue.
export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("arrivals.page");
  return { title: t("metaTitle"), description: t("metaDescription") };
}

export default async function ArrivalsLayout({ children }: { children: React.ReactNode }) {
  const t = await getTranslations("arrivals.page");
  return <AccessGate permission="queue:view" surface="arrivals" title={t("noAccessTitle")} hint={t("noAccessHint")}>{children}</AccessGate>;
}
