"use client";

import { Suspense, useEffect, useMemo } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { isPaper, type Paper } from "@nabd/ui";
import Icon from "../../../../../components/ui/Icon";
import { EmptyState, ErrorState, Skeleton } from "../../../../../components/ui/StateViews";
import { InvoiceSheet } from "../../../../../components/checkout/InvoiceSheet";
import { buildInvoiceView, documentProblem, type InvoiceDocument } from "../../../../../components/checkout/invoiceDocument";
import { api, ApiError } from "../../../../../lib/api";
import { key } from "../../../../../lib/query";
import { usePrintPaper } from "../../../../../lib/usePrintPaper";
import { formatClinicDate, formatClinicTime } from "../../../../../lib/clinicTime";
import styles from "../../../../checkout/checkout.module.css";

// FEF-047: print route for one invoice or receipt. ?kind=receipt|invoice, ?paper=a4|a5|80mm (else the clinic default),
// ?copy=original|duplicate (reprints are marked), ?auto=1 prints as soon as the document is on screen.
export default function InvoicePrintPage() {
  return <Suspense fallback={<Skeleton rows={4} label="Loading" />}><Inner /></Suspense>;
}

function Inner() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const sp = useSearchParams();
  const kind = sp.get("kind") === "receipt" ? "receipt" : "invoice";
  const copy = sp.get("copy") === "duplicate" ? "duplicate" : "original";
  const clinicDefault = usePrintPaper(kind);
  const asked = sp.get("paper");
  const paper: Paper = isPaper(asked) ? asked : clinicDefault;

  const q = useQuery({
    queryKey: key("invoice-document", { id, copy }),
    retry: false,
    gcTime: 0, // a document is never kept for the next viewer
    queryFn: ({ signal }) => api.get<InvoiceDocument>(`/billing/invoices/${encodeURIComponent(id)}/document?copy=${copy}`, { signal }),
  });
  const view = useMemo(() => {
    if (!q.data) return null;
    const when = (iso: string) => `${formatClinicDate(iso)} ${formatClinicTime(iso)}`;
    return documentProblem(q.data) ? null : buildInvoiceView(q.data, when);
  }, [q.data]);

  const auto = sp.get("auto") === "1";
  useEffect(() => {
    if (!auto || !view) return;
    const t = setTimeout(() => window.print(), 50); // let the sheet's @page rule apply first
    return () => clearTimeout(t);
  }, [auto, view]);

  if (q.isPending) return <main className={styles.page}><Skeleton rows={6} label="Loading invoice" /></main>;
  if (q.isError) {
    const s = q.error instanceof ApiError ? q.error.status : 0;
    if (s === 404) return <main className={styles.page}><EmptyState icon="inbox" title="This invoice wasn't found." /></main>;
    if (s === 409) return <main className={styles.page}><EmptyState icon="clock" title="This invoice is still a draft." hint="Only a finalised invoice can be printed." /></main>;
    return <main className={styles.page}><ErrorState message="Couldn't load the invoice." onRetry={() => void q.refetch()} /></main>;
  }
  if (!view) return <main className={styles.page}><ErrorState message="This invoice's totals don't add up, so it won't be printed. Contact support." /></main>;
  return (
    <main className={styles.page}>
      <div className={styles.printBar}>
        <button type="button" className={styles.backBtn} onClick={() => router.back()}>Back</button>
        <button type="button" className={styles.checkoutBtn} onClick={() => window.print()}><Icon name="receipt" size={14} /> Print</button>
      </div>
      <InvoiceSheet view={view} paper={paper} kind={kind} />
    </main>
  );
}
