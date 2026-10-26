// FEF-014: the generated MSW handlers with every request validated against api/openapi/core.yaml (openapi-backend).
// A request that breaks the spec (missing required field, wrong type, bad enum, missing Idempotency-Key...) gets a
// 400 Problem+JSON and is recorded; call assertNoSpecViolations() in afterEach so the test fails. Tests only.
import { http, HttpResponse } from "msw";
import { OpenAPIBackend } from "openapi-backend";
import { createHandlers } from "../src/generated/handlers.ts";

// shortcut: URL pathname as a file path is POSIX-only (CI and dev are Linux/macOS); use node:url if Windows dev appears
export const SPEC_PATH = decodeURIComponent(new URL("../../../../api/openapi/core.yaml", import.meta.url).pathname);

export type SpecViolation = { method: string; path: string; operationId?: string; errors: string[] };

type Verb = "get" | "post" | "put" | "patch" | "delete";

// The formats core.yaml uses; int32/int64/double add nothing beyond `type` here.
const FORMATS: Record<string, RegExp | true> = {
  uuid: /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
  date: /^\d{4}-\d{2}-\d{2}$/,
  "date-time": /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/i,
  byte: /^[A-Za-z0-9+/]*={0,2}$/,
  int32: true, int64: true, double: true,
};

/** OpenAPI 3.0 `minimum: 0, exclusiveMinimum: true` -> draft-07 `exclusiveMinimum: 0`; `false` is dropped. In place. */
function draft07Bounds<T>(node: T): T {
  if (Array.isArray(node)) node.forEach(draft07Bounds);
  else if (node && typeof node === "object") {
    const o = node as Record<string, unknown>;
    for (const [flag, bound] of [["exclusiveMinimum", "minimum"], ["exclusiveMaximum", "maximum"]]) {
      if (typeof o[flag] !== "boolean") continue;
      if (o[flag] && typeof o[bound] === "number") { o[flag] = o[bound]; delete o[bound]; } else delete o[flag];
    }
    Object.values(o).forEach(draft07Bounds);
  }
  return node;
}

/** baseUrl is the origin only (handler paths already start with /v1). */
export async function createValidatingHandlers(baseUrl = "*") {
  // springdoc writes OpenAPI 3.0 boolean exclusiveMinimum/Maximum, which openapi-backend's draft-07 Ajv refuses: load, map them, reload.
  const loader = new OpenAPIBackend({ definition: SPEC_PATH, quick: true, strict: true });
  await loader.init();
  const api = new OpenAPIBackend({ definition: draft07Bounds(loader.definition), quick: true, strict: true, customizeAjv: (ajv) => {
    for (const [name, format] of Object.entries(FORMATS)) ajv.addFormat(name, format);
    return ajv;
  } });
  await api.init();
  const violations: SpecViolation[] = [];

  const route = (verb: Verb) => (path: string, resolver: () => Response) =>
    http[verb](path, async ({ request }) => {
      if (path.includes("*")) return resolver(); // wildcard servlet mappings (e.g. /abdm/v3/callbacks/**) are not OpenAPI templates: openapi-backend's router throws on them
      const url = new URL(request.url);
      const text = await request.clone().text();
      let body: unknown = undefined;
      if (text) {
        try { body = JSON.parse(text); } catch { body = text; } // a non-JSON body is validated (and refused) as a string
      }
      const req = { method: verb, path: url.pathname, query: Object.fromEntries(url.searchParams), headers: Object.fromEntries(request.headers), body };
      const op = api.matchOperation(req);
      const result = op ? api.validateRequest(req, op) : { valid: false, errors: [{ message: "no operation in the spec" }] };
      if (result.valid) return resolver();
      const errors = (result.errors ?? []).map((e) => `${"instancePath" in e && e.instancePath ? e.instancePath + " " : ""}${e.message ?? "invalid"}`);
      violations.push({ method: verb.toUpperCase(), path: url.pathname, operationId: op?.operationId, errors });
      return HttpResponse.json({ type: "about:blank", title: "Request violates the API spec", status: 400, code: "mock.spec-violation",
        detail: errors.join("; ") }, { status: 400 });
    });

  const msw = { http: { get: route("get"), post: route("post"), put: route("put"), patch: route("patch"), delete: route("delete") }, HttpResponse };
  const handlers = createHandlers(msw, baseUrl);

  return {
    handlers,
    violations,
    /** Throws (fails the test) when any request so far broke the spec; clears the list. */
    assertNoSpecViolations() {
      const found = violations.splice(0);
      if (found.length) {
        throw new Error(`request(s) violate api/openapi/core.yaml:\n${found.map((v) => `  ${v.method} ${v.path} (${v.operationId ?? "?"}): ${v.errors.join("; ")}`).join("\n")}`);
      }
    },
  };
}
