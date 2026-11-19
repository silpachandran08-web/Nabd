// PLT-031 design spike: a small model of the offline outbox, a faulty network and the server guards described in
// docs/architecture/offline-sync.md. Test-only prototype: nothing in src/ imports it and it changes no product behaviour.
// The real client keeps the "durable" device state in IndexedDB; the real server keeps these maps in Postgres.
// Deterministic: every random choice comes from the seed.

export type Rng = ReturnType<typeof makeRng>;
/** mulberry32: small seeded generator, enough for a simulation. */
export function makeRng(seed: number) {
  let a = seed >>> 0;
  const next = () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  return {
    next,
    int: (n: number) => Math.floor(next() * n),
    chance: (p: number) => next() < p,
    pick: <T>(xs: readonly T[]): T => xs[Math.floor(next() * xs.length)],
    shuffle: <T>(xs: T[]): T[] => {
      for (let i = xs.length - 1; i > 0; i--) {
        const j = Math.floor(next() * (i + 1));
        [xs[i], xs[j]] = [xs[j], xs[i]];
      }
      return xs;
    },
  };
}

/** An offline action. opId is made on the device and is both the Idempotency-Key and the durable client_op_id.
 * Offline-made things are referenced by the opId that made them (visitRef, billRef), never by a server id the device cannot know. */
export type Command =
  | { type: "checkIn"; opId: string; device: string; patientId: string; doctorId: string; provisionalToken: string }
  | { type: "bill"; opId: string; device: string; visitRef: string | null; number: number; amount: number; item: string | null; qty: number }
  | { type: "payment"; opId: string; device: string; billRef: string; amount: number };
type CheckIn = Extract<Command, { type: "checkIn" }>;
type Bill = Extract<Command, { type: "bill" }>;
type Pay = Extract<Command, { type: "payment" }>;

export type Visit = { id: string; opId: string | null; patientId: string; doctorId: string; token: number | null; provisionalToken: string | null; mergedInto: string | null; billed: boolean; seq: number };
export type Invoice = { id: string; opId: string | null; series: string; number: number; visitId: string | null; amount: number; duplicateOf: string | null };
export type Payment = { id: string; opId: string | null; invoiceId: string; amount: number; held: boolean };
export type ConflictKind = "DUPLICATE_CHECK_IN" | "DUPLICATE_BILL" | "PAYMENT_HELD" | "OVERSOLD";
export type Conflict = { id: string; device: string; opId: string; kind: ConflictKind };
export type Response = { status: number; code?: string; conflicts: string[] };
export type Page = { rows: Visit[]; cursor: number };

/** The design's guards. Each can be switched off to show that the checks notice when it is missing. */
export type Options = {
  /** unique client_op_id on the created row: a re-run of the same action finds the row instead of making a second one */
  durableOpId: boolean;
  /** conflicts are stored rows the device can fetch again, not only a field of one HTTP answer */
  durableConflicts: boolean;
  /** the delta feed never passes a change whose transaction is still open */
  safeHorizon: boolean;
  /** WRONG on purpose when true: the device forgets a command as soon as it is sent */
  ackOnSend: boolean;
};
export const DESIGN: Options = { durableOpId: true, durableConflicts: true, safeHorizon: true, ackOnSend: false };

/** Numbers a device may issue past what the server has seen before it must renew online. */
export const BLOCK = 8;
/** Answers after which the device keeps the command and tries again later. */
const RETRYABLE = new Set(["IDEMPOTENCY_IN_PROGRESS", "DEPENDENCY_NOT_APPLIED", "503"]);

export class Server {
  readonly visits: Visit[] = [];
  readonly invoices: Invoice[] = [];
  readonly payments: Payment[] = [];
  readonly conflicts = new Map<string, Conflict>();
  readonly stock = new Map<string, number>();
  readonly stats = { idempotentReplays: 0, durableHits: 0, keysExpired: 0, inProgress: 0, dependencyRetries: 0, failedBeforeCommit: 0, lostBookkeeping: 0 };
  private readonly idem = new Map<string, { hash: string; response: Response | null }>(); // PLT-026 rows, key = actor:key
  private readonly visitById = new Map<string, Visit>();
  private readonly visitByOp = new Map<string, Visit>();
  private readonly invoiceByOp = new Map<string, Invoice>();
  private readonly paymentByOp = new Map<string, Payment>();
  private readonly active = new Map<string, Visit>(); // patient|doctor -> the visit that counts
  private readonly effective = new Map<string, Invoice>(); // visit id -> the bill that counts
  private readonly numbers = new Map<string, string>(); // series:number -> opId
  private readonly ceiling = new Map<string, number>();
  private readonly tokenNext = new Map<string, number>();
  private readonly conflictsByOp = new Map<string, string[]>();
  private readonly conflictsByDevice = new Map<string, string[]>();
  private readonly changes: { seq: number; visibleAt: number; row: Visit }[] = [];
  private onlineNumber = 0;
  private seq = 0;
  private clock = 0;

  constructor(private readonly rng: Rng, private readonly opt: Options) {}

  /** One delivered request, with the PLT-026 behaviour of IdempotencyFilter: key per (actor, key), only 2xx stored,
   * a non-2xx frees the key, same key + other body = 422, a second arrival while the first runs = 409. */
  handle(actor: string, cmd: Command): Response {
    this.clock++;
    const key = `${actor}:${cmd.opId}`;
    const hash = JSON.stringify(cmd);
    const seen = this.idem.get(key);
    if (seen) {
      if (seen.hash !== hash) return { status: 422, code: "IDEMPOTENCY_KEY_REUSED", conflicts: [] };
      if (!seen.response) {
        this.stats.inProgress++;
        return { status: 409, code: "IDEMPOTENCY_IN_PROGRESS", conflicts: [] };
      }
      this.stats.idempotentReplays++;
      return seen.response;
    }
    if (this.rng.chance(0.03)) { // the server failed before its transaction: nothing happened
      this.stats.failedBeforeCommit++;
      return { status: 503, code: "503", conflicts: [] };
    }
    this.idem.set(key, { hash, response: null });
    const res = this.apply(cmd);
    if (res.status >= 300) this.idem.delete(key);
    else if (this.rng.chance(0.03)) this.stats.lostBookkeeping++; // the action committed but the row stayed in_progress (crash after commit)
    else this.idem.set(key, { hash, response: res });
    return res;
  }

  /** Time passes: an in_progress row is taken over after its 5 minute lease, a completed one is purged after 24 hours. */
  expire() {
    for (const [k, v] of this.idem) {
      if (this.rng.chance(v.response ? 0.05 : 0.5)) {
        this.idem.delete(k);
        this.stats.keysExpired++;
      }
    }
  }

  private apply(cmd: Command): Response {
    if (cmd.type === "checkIn") return this.checkIn(cmd);
    if (cmd.type === "bill") return this.bill(cmd);
    return this.pay(cmd);
  }

  /** The row this op already made: answer as success again, with its conflicts read back from storage. */
  private again(opId: string): Response {
    this.stats.durableHits++;
    return { status: 200, conflicts: this.opt.durableConflicts ? this.conflictsByOp.get(opId) ?? [] : [] };
  }

  private checkIn(c: CheckIn): Response {
    if (this.opt.durableOpId && this.visitByOp.has(c.opId)) return this.again(c.opId);
    // first accepted wins; a later check-in of the same patient with the same doctor is kept as a merged entry, so the
    // slip the patient holds and anything billed against it still resolve
    const winner = this.active.get(`${c.patientId}|${c.doctorId}`);
    this.newVisit(c.patientId, c.doctorId, c.opId, c.provisionalToken, winner?.id ?? null);
    return { status: 201, conflicts: winner ? [this.raise(c.device, c.opId, "DUPLICATE_CHECK_IN")] : [] };
  }

  private bill(c: Bill): Response {
    if (this.opt.durableOpId && this.invoiceByOp.has(c.opId)) return this.again(c.opId);
    let visit: Visit | undefined;
    if (c.visitRef) {
      const ref = this.visitById.get(c.visitRef) ?? this.visitByOp.get(c.visitRef);
      if (!ref) return this.notYet();
      visit = ref.mergedInto ? this.visitById.get(ref.mergedInto) : ref;
    }
    if (c.number > (this.ceiling.get(c.device) ?? 0)) return { status: 409, code: "NUMBER_OUT_OF_BLOCK", conflicts: [] };
    const numberKey = `${c.device}:${c.number}`;
    const owner = this.numbers.get(numberKey);
    if (owner && owner !== c.opId) return { status: 409, code: "NUMBER_COLLISION", conflicts: [] };
    this.numbers.set(numberKey, c.opId);
    // The number was printed on a bill the patient holds, so the document is always recorded. If the visit already has a
    // bill, this one is kept but flagged for cancellation by a person; it never counts as a second charge.
    const first = visit ? this.effective.get(visit.id) : undefined;
    const inv: Invoice = { id: `i${this.invoices.length + 1}`, opId: c.opId, series: c.device, number: c.number, visitId: visit?.id ?? null, amount: c.amount, duplicateOf: first?.id ?? null };
    this.invoices.push(inv);
    this.invoiceByOp.set(c.opId, inv);
    const raised: string[] = [];
    if (first) raised.push(this.raise(c.device, c.opId, "DUPLICATE_BILL"));
    else if (visit) {
      this.effective.set(visit.id, inv);
      visit.billed = true;
      this.touch(visit);
    }
    if (c.item) { // the goods already left the counter: record the movement, flag stock that went below zero
      const left = (this.stock.get(c.item) ?? 0) - c.qty;
      this.stock.set(c.item, left);
      if (left < 0) raised.push(this.raise(c.device, c.opId, "OVERSOLD"));
    }
    return { status: 201, conflicts: raised };
  }

  private pay(c: Pay): Response {
    if (this.opt.durableOpId && this.paymentByOp.has(c.opId)) return this.again(c.opId);
    const inv = this.invoiceByOp.get(c.billRef);
    if (!inv) return this.notYet();
    // money that was taken is always recorded; against a flagged bill it is held for refund or allocation
    const p: Payment = { id: `pay${this.payments.length + 1}`, opId: c.opId, invoiceId: inv.id, amount: c.amount, held: inv.duplicateOf !== null };
    this.payments.push(p);
    this.paymentByOp.set(c.opId, p);
    return { status: 201, conflicts: p.held ? [this.raise(c.device, c.opId, "PAYMENT_HELD")] : [] };
  }

  /** The thing this command builds on has not arrived yet (requests can overtake each other): not a conflict, try again. */
  private notYet(): Response {
    this.stats.dependencyRetries++;
    return { status: 409, code: "DEPENDENCY_NOT_APPLIED", conflicts: [] };
  }

  private raise(device: string, opId: string, kind: ConflictKind): string {
    const id = `${kind}:${opId}`;
    if (!this.conflicts.has(id)) {
      this.conflicts.set(id, { id, device, opId, kind });
      this.conflictsByOp.set(opId, [...(this.conflictsByOp.get(opId) ?? []), id]);
      this.conflictsByDevice.set(device, [...(this.conflictsByDevice.get(device) ?? []), id]);
    }
    return id;
  }

  private newVisit(patientId: string, doctorId: string, opId: string | null, provisionalToken: string | null, mergedInto: string | null): Visit {
    const token = mergedInto ? null : this.tokenNext.get(doctorId) ?? 1;
    if (token) this.tokenNext.set(doctorId, token + 1);
    const v: Visit = { id: `v${this.visits.length + 1}`, opId, patientId, doctorId, token, provisionalToken, mergedInto, billed: false, seq: 0 };
    this.visits.push(v);
    this.visitById.set(v.id, v);
    if (opId) this.visitByOp.set(opId, v);
    if (!mergedInto) this.active.set(`${patientId}|${doctorId}`, v);
    this.touch(v);
    return v;
  }

  /** change_seq: taken when the row is written, but some transactions commit late, after higher numbers are already visible. */
  private touch(v: Visit) {
    v.seq = ++this.seq;
    this.changes.push({ seq: v.seq, visibleAt: this.clock + (this.rng.chance(0.1) ? 1 + this.rng.int(6) : 0), row: { ...v } });
  }

  /** Delta sync: rows with change_seq above the cursor. The safe horizon stops before the first row that is not
   * committed yet, so the next page still returns it; without it the cursor passes that row for good. */
  feed(cursor: number): Page {
    const rows: Visit[] = [];
    let next = cursor;
    for (const c of this.changes) {
      if (c.seq <= cursor) continue;
      if (c.visibleAt > this.clock) {
        if (this.opt.safeHorizon) break;
        continue;
      }
      rows.push(c.row);
      next = c.seq;
    }
    return { rows, cursor: next };
  }

  /** Lease renewal: the device may issue up to BLOCK numbers past what it reports, never past the old ceiling + BLOCK. Safe to repeat. */
  renew(device: string, reportedNext: number): number {
    const cur = this.ceiling.get(device) ?? 0;
    const ceiling = Math.max(cur, Math.min(reportedNext, cur + 1) - 1 + BLOCK);
    this.ceiling.set(device, ceiling);
    return ceiling;
  }

  pendingConflicts(device: string): string[] {
    return this.opt.durableConflicts ? this.conflictsByDevice.get(device) ?? [] : [];
  }

  // --- work done by staff who are online, straight on the server ---
  onlineCheckIn(patientId: string, doctorId: string) {
    this.clock++;
    if (!this.active.has(`${patientId}|${doctorId}`)) this.newVisit(patientId, doctorId, null, null, null);
  }

  onlineBill(amount: number) {
    this.clock++;
    const open = this.visits.slice(-12).filter((v) => !v.mergedInto && !v.billed);
    if (!open.length) return;
    const visit = this.rng.pick(open);
    const inv: Invoice = { id: `i${this.invoices.length + 1}`, opId: null, series: "online", number: ++this.onlineNumber, visitId: visit.id, amount, duplicateOf: null };
    this.invoices.push(inv);
    this.effective.set(visit.id, inv);
    this.payments.push({ id: `pay${this.payments.length + 1}`, opId: null, invoiceId: inv.id, amount, held: false });
    visit.billed = true;
    this.touch(visit);
  }

  onlineStock(item: string, delta: number) {
    this.clock++;
    const have = this.stock.get(item) ?? 0;
    if (have + delta >= 0) this.stock.set(item, have + delta);
  }

  /** Every open transaction has committed. */
  settle() {
    this.clock += 100;
  }
}

export class Device {
  // Durable state: IndexedDB in the real client. A command and the counter it used are written in one transaction,
  // before anything is printed, so a crash can neither reuse nor skip a number.
  outbox: Command[] = [];
  readonly dead: Command[] = [];
  nextNumber = 1;
  readonly inbox = new Set<string>();
  cursor = 0;
  readonly mirror = new Map<string, Visit>();
  private provisional = 0;
  private ops = 0;
  private readonly checkedIn = new Set<string>();
  private readonly billedHere = new Set<string>();
  private readonly ownOpen: string[] = [];

  constructor(readonly code: string, public ceiling: number) {}

  private op() {
    return `${this.code}-op${++this.ops}`;
  }

  /** null: this device already checked that patient in (the local copy applies the same rule as the server). */
  checkIn(patientId: string, doctorId: string): CheckIn | null {
    const key = `${patientId}|${doctorId}`;
    if (this.checkedIn.has(key)) return null;
    this.checkedIn.add(key);
    const c: CheckIn = { type: "checkIn", opId: this.op(), device: this.code, patientId, doctorId, provisionalToken: `${this.code}-${++this.provisional}` };
    this.outbox.push(c);
    this.ownOpen.push(c.opId);
    return c;
  }

  /** A visit this device believes is unbilled: one of its own offline check-ins, else a recent one from its (possibly stale) copy. */
  pickVisit(rng: Rng): string | null {
    if (this.ownOpen.length && rng.chance(0.6)) return this.ownOpen.shift() ?? null;
    const open = [...this.mirror.values()].filter((v) => !v.mergedInto && !v.billed && !this.billedHere.has(v.id)).slice(-10);
    return open.length ? rng.pick(open).id : null;
  }

  /** null: the leased block is used up, the device must renew online before it can bill again. */
  bill(visitRef: string | null, amount: number, item: string | null = null, qty = 0): Bill | null {
    if (this.nextNumber > this.ceiling) return null;
    if (visitRef) this.billedHere.add(visitRef);
    const c: Bill = { type: "bill", opId: this.op(), device: this.code, visitRef, number: this.nextNumber++, amount, item, qty };
    this.outbox.push(c);
    return c;
  }

  pay(billRef: string, amount: number): Pay {
    const c: Pay = { type: "payment", opId: this.op(), device: this.code, billRef, amount };
    this.outbox.push(c);
    return c;
  }

  /** "apply only if newer": a repeated or late page cannot move the copy backwards. */
  apply(page: Page) {
    for (const r of page.rows) {
      const cur = this.mirror.get(r.id);
      if (!cur || r.seq > cur.seq) this.mirror.set(r.id, r);
    }
    this.cursor = Math.max(this.cursor, page.cursor);
  }
}

export class Network {
  readonly stats = { sends: 0, timeouts: 0, lateDeliveries: 0, stalePages: 0 };
  private limbo: { actor: string; cmd: Command }[] = [];
  private readonly stale = new Map<string, Page>();

  constructor(private readonly rng: Rng, private readonly server: Server) {}

  /** One HTTP attempt. null = the device saw a timeout; the request may or may not have run. */
  send(actor: string, cmd: Command, reliable: boolean): Response | null {
    this.stats.sends++;
    if (!reliable) {
      const r = this.rng.next();
      if (r < 0.16) {
        if (r >= 0.08) this.limbo.push({ actor, cmd }); // not lost, only slow: it arrives later, after newer requests
        this.stats.timeouts++;
        return null;
      }
    }
    const res = this.server.handle(actor, cmd);
    if (reliable) return res;
    if (this.rng.chance(0.08)) this.limbo.push({ actor, cmd }); // duplicated on the way
    if (this.rng.chance(0.1)) { // the answer was lost
      this.stats.timeouts++;
      return null;
    }
    return res;
  }

  /** Slow and duplicated requests arrive, in any order; nobody is waiting for their answers. */
  deliverLate(p: number) {
    const keep: { actor: string; cmd: Command }[] = [];
    for (const m of this.rng.shuffle(this.limbo)) {
      if (this.rng.chance(p)) {
        this.server.handle(m.actor, m.cmd);
        this.stats.lateDeliveries++;
      } else keep.push(m);
    }
    this.limbo = keep;
  }

  /** One delta pull. The page can be lost, and an older page can arrive again after a newer one. */
  pull(d: Device, reliable: boolean) {
    const old = this.stale.get(d.code);
    const page = this.server.feed(d.cursor);
    if (!reliable && this.rng.chance(0.15)) return;
    d.apply(page);
    if (old && this.rng.chance(0.3)) {
      d.apply(old);
      this.stats.stalePages++;
    }
    this.stale.set(d.code, page);
  }
}

/** One reconnect of one device: renew the lease, replay the outbox, fetch conflicts, pull changes. Any step can fail. */
export function reconnect(d: Device, server: Server, net: Network, rng: Rng, opt: Options, reliable = false) {
  // PLT-026 keys are per staff member: when someone else is signed in for the replay, only the durable op id protects
  const actor = rng.chance(0.1) ? `${d.code}-relief` : `${d.code}-staff`;
  if (reliable || rng.chance(0.9)) d.ceiling = Math.max(d.ceiling, server.renew(d.code, d.nextNumber));
  let sends = reliable ? 400 : 1 + rng.int(30); // then the link drops again
  const window = rng.chance(0.5) ? 1 : 4; // 1: strictly in order; 4: sent together, arrival order not guaranteed
  while (d.outbox.length && sends > 0) {
    for (const cmd of rng.shuffle(d.outbox.slice(0, window))) {
      if (sends-- <= 0) break;
      const res = net.send(actor, cmd, reliable);
      if (rng.chance(0.2)) net.deliverLate(0.3);
      if (opt.ackOnSend) {
        d.outbox = d.outbox.filter((c) => c !== cmd);
        continue;
      }
      if (!res) continue; // timeout: keep it; the resend carries the same key and the same bytes
      for (const c of res.conflicts) d.inbox.add(c);
      if (res.status < 300) d.outbox = d.outbox.filter((c) => c !== cmd);
      else if (!RETRYABLE.has(res.code ?? "")) { // an answer the device cannot act on: parked for a person, never dropped
        d.outbox = d.outbox.filter((c) => c !== cmd);
        d.dead.push(cmd);
      }
    }
  }
  if (reliable || rng.chance(0.85)) for (const c of server.pendingConflicts(d.code)) d.inbox.add(c);
  net.pull(d, reliable);
}

export type Truth = { checkIns: CheckIn[]; bills: Bill[]; payments: Pay[] };
export type Result = { server: Server; devices: Device[]; truth: Truth; stats: Record<string, number> };

/**
 * `reconnects` rounds. In each round every device works offline, online staff work on the server, time passes, and one
 * randomly chosen device gets a (bad) connection. At the end the links are good until every outbox is empty.
 * `truth` is the harness's own record of what happened at the desks, kept outside the devices and the server.
 */
export function simulate(seed: number, reconnects: number, overrides: Partial<Options> = {}): Result {
  const opt = { ...DESIGN, ...overrides };
  const rng = makeRng(seed);
  const server = new Server(rng, opt);
  const net = new Network(rng, server);
  const items = ["item-1", "item-2", "item-3"];
  for (const i of items) server.onlineStock(i, 40);
  const desks = ["A", "B"].map((code) => new Device(code, server.renew(code, 1)));
  const counter = new Device("P", server.renew("P", 1)); // pharmacy counter
  const devices = [...desks, counter];
  const truth: Truth = { checkIns: [], bills: [], payments: [] };
  const doctors = ["dr-1", "dr-2"];
  const recent: string[] = [];
  let patients = 0;
  let blocked = 0;
  const patient = () => {
    if (recent.length && rng.chance(0.3)) return rng.pick(recent); // someone another desk may also be handling
    const p = `patient-${++patients}`;
    recent.push(p);
    if (recent.length > 12) recent.shift();
    return p;
  };
  const sell = (d: Device, visitRef: string | null, item: string | null, qty: number) => {
    const amount = 100 + rng.int(900);
    const b = d.bill(visitRef, amount, item, qty);
    if (!b) return void blocked++;
    truth.bills.push(b);
    const part = rng.chance(0.3) ? 1 + rng.int(amount - 1) : amount; // sometimes a split payment
    truth.payments.push(d.pay(b.opId, part));
    if (part < amount) truth.payments.push(d.pay(b.opId, amount - part));
  };

  for (let round = 0; round < reconnects; round++) {
    for (const d of desks) {
      for (let n = rng.int(4); n > 0; n--) {
        if (rng.chance(0.45)) {
          const c = d.checkIn(patient(), rng.pick(doctors));
          if (c) truth.checkIns.push(c);
        } else {
          const visit = d.pickVisit(rng);
          if (visit) sell(d, visit, null, 0);
        }
      }
    }
    for (let n = rng.int(4); n > 0; n--) sell(counter, null, rng.pick(items), 1 + rng.int(3));
    if (rng.chance(0.5)) server.onlineCheckIn(patient(), rng.pick(doctors));
    if (rng.chance(0.5)) server.onlineBill(100 + rng.int(900));
    if (rng.chance(0.5)) server.onlineStock(rng.pick(items), -(1 + rng.int(3)));
    for (const i of items) if ((server.stock.get(i) ?? 0) < 10 && rng.chance(0.3)) server.onlineStock(i, 30);
    server.expire();
    net.deliverLate(0.2);
    reconnect(rng.pick(devices), server, net, rng, opt);
  }

  // the internet is back for good: stragglers arrive, every device syncs until it has nothing left to send
  for (let i = 0; i < 20; i++) {
    net.deliverLate(1);
    server.expire();
    server.settle();
    for (const d of devices) reconnect(d, server, net, rng, opt, true);
    if (i > 0 && devices.every((d) => !d.outbox.length)) break;
  }
  server.settle();
  for (const d of devices) d.apply(server.feed(d.cursor)); // one last pull, after the last device has finished sending

  const kinds = (k: ConflictKind) => [...server.conflicts.values()].filter((c) => c.kind === k).length;
  return {
    server, devices, truth,
    stats: {
      reconnects,
      checkIns: truth.checkIns.length, bills: truth.bills.length, payments: truth.payments.length,
      paidMinor: truth.payments.reduce((s, p) => s + p.amount, 0),
      billsStoppedByBlock: blocked,
      ...net.stats, ...server.stats,
      duplicateCheckIns: kinds("DUPLICATE_CHECK_IN"), duplicateBills: kinds("DUPLICATE_BILL"), paymentsHeld: kinds("PAYMENT_HELD"), oversold: kinds("OVERSOLD"),
    },
  };
}

/** Everything that must hold after the final sync. An empty list means the run is clean. */
export function violations({ server, devices, truth }: Result): string[] {
  const out: string[] = [];
  const group = <T>(xs: readonly T[], key: (x: T) => string | null) => {
    const m = new Map<string, T[]>();
    for (const x of xs) {
      const k = key(x);
      if (k !== null) m.set(k, [...(m.get(k) ?? []), x]);
    }
    return m;
  };
  const device = new Map(devices.map((d) => [d.code, d]));
  const surfaced = (code: string, id: string) => server.conflicts.has(id) && device.get(code)?.inbox.has(id);
  const consecutive = (ns: number[]) => ns.sort((a, b) => a - b).every((n, i) => n === i + 1);

  // 1. no duplicate bills: one invoice per action, one invoice per number, one bill that counts per visit
  const invoiceByOp = group(server.invoices, (i) => i.opId);
  for (const [op, hits] of invoiceByOp) if (hits.length > 1) out.push(`duplicate bill: ${op} made ${hits.length} invoices`);
  for (const [n, hits] of group(server.invoices, (i) => `${i.series}:${i.number}`)) if (hits.length > 1) out.push(`duplicate bill number ${n}`);
  for (const [v, hits] of group(server.invoices, (i) => (i.duplicateOf ? null : i.visitId))) if (hits.length > 1) out.push(`visit ${v} has ${hits.length} bills that count`);

  // 2. no lost bill, and each series is consecutive from 1 with no gap
  for (const b of truth.bills) {
    const hit = invoiceByOp.get(b.opId)?.[0];
    if (!hit) out.push(`lost bill ${b.opId}`);
    else if (hit.series !== b.device || hit.number !== b.number || hit.amount !== b.amount) out.push(`bill ${b.opId} changed on the way`);
    else if (hit.duplicateOf && !surfaced(b.device, `DUPLICATE_BILL:${b.opId}`)) out.push(`conflict not surfaced: DUPLICATE_BILL:${b.opId}`);
  }
  for (const [series, hits] of group(server.invoices, (i) => i.series)) {
    if (!consecutive(hits.map((i) => i.number))) out.push(`series ${series} is not consecutive`);
    const d = device.get(series);
    if (d && hits.length !== d.nextNumber - 1) out.push(`series ${series}: device issued ${d.nextNumber - 1}, server holds ${hits.length}`);
  }

  // 3. no lost payment: every payment taken is on the server once, with its amount; held exactly when its bill is flagged
  const paymentByOp = group(server.payments, (p) => p.opId);
  const invoiceById = new Map(server.invoices.map((i) => [i.id, i]));
  for (const p of truth.payments) {
    const hits = paymentByOp.get(p.opId) ?? [];
    if (!hits.length) out.push(`lost payment ${p.opId}`);
    else if (hits.length > 1) out.push(`duplicate payment ${p.opId}`);
    else if (hits[0].amount !== p.amount) out.push(`payment ${p.opId} changed on the way`);
    else if (hits[0].held !== Boolean(invoiceById.get(hits[0].invoiceId)?.duplicateOf)) out.push(`payment ${p.opId} has the wrong hold state`);
    else if (hits[0].held && !surfaced(p.device, `PAYMENT_HELD:${p.opId}`)) out.push(`conflict not surfaced: PAYMENT_HELD:${p.opId}`);
  }
  const took = truth.payments.reduce((s, p) => s + p.amount, 0);
  const booked = server.payments.reduce((s, p) => s + (p.opId ? p.amount : 0), 0);
  if (took !== booked) out.push(`money taken offline ${took} != money booked ${booked}`);

  // 4. check-ins and tokens: every check-in exists once, one visit that counts per patient and doctor, real tokens 1..n per doctor
  const visitByOp = group(server.visits, (v) => v.opId);
  for (const c of truth.checkIns) {
    const hits = visitByOp.get(c.opId) ?? [];
    if (hits.length !== 1) out.push(`check-in ${c.opId} exists ${hits.length} times`);
    else if (hits[0].provisionalToken !== c.provisionalToken) out.push(`check-in ${c.opId} lost its provisional token`);
    else if (hits[0].mergedInto && !surfaced(c.device, `DUPLICATE_CHECK_IN:${c.opId}`)) out.push(`conflict not surfaced: DUPLICATE_CHECK_IN:${c.opId}`);
  }
  for (const [k, hits] of group(server.visits, (v) => (v.mergedInto ? null : `${v.patientId}|${v.doctorId}`))) if (hits.length > 1) out.push(`${k} is checked in ${hits.length} times`);
  for (const [dr, hits] of group(server.visits, (v) => (v.mergedInto ? null : v.doctorId))) if (!consecutive(hits.map((v) => v.token ?? 0))) out.push(`tokens of ${dr} are not 1..n`);
  for (const [t, hits] of group(server.visits, (v) => v.provisionalToken)) if (hits.length > 1) out.push(`provisional token ${t} used ${hits.length} times`);

  // 5. every conflict the server holds reached the device that caused it; stock below zero was flagged
  for (const c of server.conflicts.values()) if (!surfaced(c.device, c.id)) out.push(`conflict not surfaced: ${c.id}`);
  const oversold = [...server.conflicts.values()].some((c) => c.kind === "OVERSOLD");
  for (const [item, left] of server.stock) if (left < 0 && !oversold) out.push(`${item} is below zero and nobody was told`);

  // 6. nothing is stuck or parked, and every device's copy equals the server
  for (const d of devices) {
    if (d.outbox.length) out.push(`device ${d.code} still holds ${d.outbox.length} unsent commands`);
    if (d.dead.length) out.push(`device ${d.code} parked ${d.dead.length} commands`);
    const behind = server.visits.filter((v) => d.mirror.get(v.id)?.seq !== v.seq).length;
    if (behind) out.push(`device ${d.code}: ${behind} visits differ from the server`);
  }
  return out;
}
