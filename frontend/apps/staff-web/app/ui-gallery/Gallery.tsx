"use client";

import { useState } from "react";
import { Avatar, Badge, Button, Checkbox, EmptyState, ErrorState, Field, IconButton, Input, Kpi, Segmented, SelectMenu, Skeleton, StatusPill, Switch, Table, Tabs, Textarea, ToastProvider, useToast, type Column } from "@nabd/ui";

type Row = { id: string; name: string; status: "ok" | "warn" | "danger"; n: number };
const rows = (n: number): Row[] => Array.from({ length: n }, (_, i) => ({ id: `r${i}`, name: `Patient ${String(i).padStart(3, "0")}`, status: (["ok", "warn", "danger"] as const)[i % 3], n: (i * 7) % 50 }));
const cols: Column<Row>[] = [
  { key: "name", header: "Name", render: (r) => r.name, sortValue: (r) => r.name },
  { key: "status", header: "Status", render: (r) => <StatusPill tone={r.status} label={r.status === "ok" ? "Done" : r.status === "warn" ? "Waiting" : "Late"} /> },
  { key: "n", header: "Count", render: (r) => r.n, sortValue: (r) => r.n },
];

function Inner() {
  const toast = useToast();
  const [tab, setTab] = useState("a");
  const [sel, setSel] = useState("x");
  const [seg, setSeg] = useState("day");
  const [picked, setPicked] = useState("");
  return (
    <main style={{ padding: 16, display: "grid", gap: 16 }} id="gallery">
      <h1>UI gallery</h1>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        <Button tone="primary" onClick={() => toast({ message: "Saved", tone: "ok" })}>Save</Button>
        <Button tone="danger" onClick={() => toast({ message: "Failed to save", tone: "danger" })}>Fail</Button>
        <Button loading>Loading</Button>
        <Button disabled>Disabled</Button>
        <IconButton icon="plus" label="Add item" />
        <Badge count={3} label="unread" /><Avatar name="Rana Reception" /><Kpi label="Waiting" value={12} />
      </div>
      <Field label="Phone" hint="With country code" error="Enter a valid number" required>{(p) => <Input {...p} defaultValue="12" />}</Field>
      <Field label="Notes">{(p) => <Textarea {...p} />}</Field>
      <Checkbox label="Remind me" /><Switch label="Notifications" />
      <Segmented label="Range" value={seg} onChange={setSeg} options={[{ value: "day", label: "Day" }, { value: "week", label: "Week" }]} />
      <Tabs label="Sections" value={tab} onChange={setTab} tabs={[{ id: "a", label: "One" }, { id: "b", label: "Two" }, { id: "c", label: "Three" }]} />
      <div id="tab-now">{tab}</div>
      <SelectMenu label="Pick a doctor" value={sel} onChange={setSel} options={[{ value: "x", label: "Dr A" }, { value: "y", label: "Dr B" }]} />
      <div id="sel-now">{sel}</div>
      <Table caption="Small table" columns={cols} rows={rows(8)} rowKey={(r) => r.id} onRowActivate={(r) => setPicked(r.id)} />
      <div id="picked">{picked}</div>
      <Table caption="Big table" columns={cols} rows={rows(1000)} rowKey={(r) => r.id} onRowActivate={(r) => setPicked(r.id)} />
      <Table caption="Empty table" columns={cols} rows={[]} rowKey={(r) => r.id} empty={<EmptyState title="No patients" hint="Nothing to show" />} />
      <Skeleton rows={2} /><ErrorState message="Could not load" onRetry={() => toast({ message: "Retrying" })} />
    </main>
  );
}
export default function Gallery() { return <ToastProvider><Inner /></ToastProvider>; }
