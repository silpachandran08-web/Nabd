import type { Metadata } from "next";
import { connection } from "next/server";
import ExportDownload from "../../components/ExportDownload";
import { LINK_ID } from "../../lib/security";
import { currentLocale, messagesFor } from "../../lib/messages";

export const metadata: Metadata = { title: "Nabd" };

export default async function ExportDownloadPage({ params }: { params: Promise<{ token: string }> }) {
  await connection();
  const { token } = await params;
  const locale = await currentLocale();
  // A malformed token is shown exactly like an expired one.
  return <ExportDownload token={LINK_ID.test(token) ? token : null} messages={messagesFor(locale)} locale={locale} />;
}
