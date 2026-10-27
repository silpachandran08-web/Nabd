import type { Metadata } from "next";
import { connection } from "next/server";
import ProxyInvite from "../../components/ProxyInvite";
import { LINK_ID } from "../../lib/security";
import { currentLocale, messagesFor } from "../../lib/messages";

export const metadata: Metadata = { title: "Nabd" }; // generic on purpose: the tab title must not say who asked

export default async function ProxyInvitePage({ params }: { params: Promise<{ token: string }> }) {
  await connection();
  const { token } = await params;
  const locale = await currentLocale();
  // A malformed token is shown exactly like an expired one.
  return <ProxyInvite token={LINK_ID.test(token) ? token : null} messages={messagesFor(locale)} />;
}
