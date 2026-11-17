import type { Metadata } from "next";
import { connection } from "next/server";
import RxVerifyView from "../../components/RxVerifyView";
import { isToken } from "../../lib/rxVerify";
import { currentLocale, messagesFor } from "../../lib/messages";

export const metadata: Metadata = { title: "Nabd" };

/** CLN-023: where a scanned prescription QR lands. A malformed token reads like an unknown one, without a call. */
export default async function RxVerifyPage({ params }: { params: Promise<{ token: string }> }) {
  await connection();
  const { token } = await params;
  const locale = await currentLocale();
  return <RxVerifyView token={isToken(token) ? token : null} messages={messagesFor(locale)} locale={locale} />;
}
