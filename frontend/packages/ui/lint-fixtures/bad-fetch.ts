// Lint fixture (FEF-012): MUST fail. packages/ui has no data fetching.
export const load = () => fetch("/v1/patients");
export const load2 = () => globalThis.fetch("/v1/patients");
