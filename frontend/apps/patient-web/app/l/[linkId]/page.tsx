import type { Metadata } from "next";
import { connection } from "next/server";
import LinkFlow from "../../components/LinkFlow";
import { LINK_ID } from "../../lib/security";
import { currentLocale, messagesFor } from "../../lib/messages";

export const metadata: Metadata = { title: "Nabd" }; // generic on purpose: the tab title must not say what the link holds

export default async function LinkPage({ params }: { params: Promise<{ linkId: string }> }) {
  await connection();
  const { linkId } = await params;
  const locale = await currentLocale();
  // A malformed id is shown exactly like an expired one (no hint that the id was never valid).
  return <LinkFlow linkId={LINK_ID.test(linkId) ? linkId : null} locale={locale} messages={messagesFor(locale)} />;
}
