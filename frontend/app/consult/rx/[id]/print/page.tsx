import RxPrint from "../../../../components/consult/RxPrint";

// DOC-N1: /consult/rx/{queueEntryId}/print. The id is the visit (queue entry) id, as for GET /clinical/prescriptions/{id}.
export default async function RxPrintPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <RxPrint queueEntryId={id} />;
}
