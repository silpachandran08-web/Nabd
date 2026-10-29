import { notFound } from "next/navigation";
import QaThrow from "../../../components/ui/QaThrow";

export default function QaThrowPage() {
  if (process.env.NABD_QA_THROW !== "1") notFound();
  return <QaThrow />;
}
