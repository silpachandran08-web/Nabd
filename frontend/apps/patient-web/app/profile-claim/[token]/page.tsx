import type { Metadata } from "next";
import { connection } from "next/server";
import ProfileClaim from "../../components/ProfileClaim";
import { LINK_ID } from "../../lib/security";
import { currentLocale, messagesFor } from "../../lib/messages";

export const metadata: Metadata = { title: "Nabd" }; // generic on purpose: the tab title must not say whose profile it is

export default async function ProfileClaimPage({ params }: { params: Promise<{ token: string }> }) {
  await connection();
  const { token } = await params;
  const locale = await currentLocale();
  // A malformed token is shown exactly like an expired one.
  return <ProfileClaim token={LINK_ID.test(token) ? token : null} messages={messagesFor(locale)} locale={locale} />;
}
