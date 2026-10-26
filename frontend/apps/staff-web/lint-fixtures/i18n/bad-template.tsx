// FEF-038 must-fail fixture: a template literal builds user-facing text (JSX child and text attribute).
export function Bad({ n, name }: { n: number; name: string }) {
  return <p aria-label={`Open ${name}`}>{`${n} waiting`}</p>;
}
