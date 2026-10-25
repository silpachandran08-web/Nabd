export default function Ok({ t }: { t: (k: string) => string }) {
  return <p>{t("hello")} · 12</p>;
}
