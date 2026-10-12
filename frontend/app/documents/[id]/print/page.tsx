"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import AccessGate from "../../../components/ui/AccessGate";
import Icon from "../../../components/ui/Icon";
import { EmptyState, ErrorState, Skeleton } from "../../../components/ui/StateViews";
import DocumentSheet from "../../../components/consult/DocumentSheet";
import { DOC_LABEL, type ClinicalDocument } from "../../../components/consult/documents";
import styles from "./print.module.css";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

type Load = { state: "loading" } | { state: "ok"; doc: ClinicalDocument } | { state: "missing" } | { state: "error" };

// DOC-030: print-friendly view of one clinical document. Only an ISSUED document is printable; the sheet is
// rendered from the snapshot taken at issue time, so it never changes after the fact.
export default function DocumentPrintPage() {
  return (
    <AccessGate permission="clinical:view" surface="clinical documents">
      <PrintView />
    </AccessGate>
  );
}

function PrintView() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const [load, setLoad] = useState<Load>({ state: "loading" });

  const fetchDoc = useCallback(async () => {
    setLoad({ state: "loading" });
    try {
      const token = localStorage.getItem("nabd_access_token");
      if (!token) { router.replace("/login"); return; }
      const res = await fetch(`${API_BASE}/clinical/documents/${id}`, { headers: { Authorization: `Bearer ${token}` } });
      if (res.status === 401) { localStorage.removeItem("nabd_access_token"); router.replace("/login"); return; }
      if (res.status === 404 || res.status === 403) { setLoad({ state: "missing" }); return; }
      if (!res.ok) { setLoad({ state: "error" }); return; }
      setLoad({ state: "ok", doc: (await res.json()) as ClinicalDocument });
    } catch {
      setLoad({ state: "error" });
    }
  }, [id, router]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void fetchDoc();
  }, [fetchDoc]);

  return (
    <main className={styles.page}>
      {load.state === "loading" && <Skeleton rows={5} label="Loading document" />}
      {load.state === "error" && <ErrorState message="Couldn't load this document." onRetry={() => void fetchDoc()} />}
      {load.state === "missing" && <EmptyState icon="inbox" title="This document wasn't found." hint="It may belong to another clinic, or have been removed." />}
      {load.state === "ok" && load.doc.status !== "issued" && (
        <EmptyState icon="clock" title="This document hasn't been issued yet." hint="Only issued documents can be printed. Issue it from the consultation's Close step." />
      )}
      {load.state === "ok" && load.doc.status === "issued" && (
        <>
          <div className={`${styles.toolbar} ${styles.noPrint}`}>
            <h1 className={styles.title}>{DOC_LABEL[load.doc.type]}</h1>
            <div className={styles.toolbarActions}>
              <Link className={styles.btn} href="/consult">Back to consultation</Link>
              <button type="button" className={styles.btnPrimary} onClick={() => window.print()}><Icon name="list" size={14} /> Print</button>
            </div>
          </div>
          <div className={styles.printArea}>
            <DocumentSheet type={load.doc.type} body={load.doc.body} header={load.doc.header} issuedAt={load.doc.issuedAt} />
          </div>
          <p className={`${styles.note} ${styles.noPrint}`}>Sharing by WhatsApp or a secure link is not available yet.</p>
        </>
      )}
    </main>
  );
}
