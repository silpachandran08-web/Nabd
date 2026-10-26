"use client";

// FEF-029 test hook: renders and throws, so an e2e can prove each route group's error boundary. The pages that mount it answer 404 unless NABD_QA_THROW=1 (preview / test builds only).
export default function QaThrow(): never {
  throw new TypeError("qa forced render error");
}
