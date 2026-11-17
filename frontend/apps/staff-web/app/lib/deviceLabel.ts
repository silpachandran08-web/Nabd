// PLT-013: the X-Device-Label this browser sends when it signs in or activates break-glass ("Front desk PC 2"), stored by
// Core on the session and the grant. Display only: the server never trusts it for a security decision.
const KEY = "nabd_device_label";
export const DEVICE_LABEL_MAX = 64;

/** Printable ASCII only (fetch refuses header values outside Latin-1), no angle brackets, whitespace collapsed, <= 64. */
export function sanitiseDeviceLabel(raw: string): string {
  return raw.replace(/[^\x20-\x7E]|[<>]/g, " ").replace(/\s+/g, " ").trim().slice(0, DEVICE_LABEL_MAX).trim();
}

/** A short default until someone names the device, e.g. "Chrome on Windows". */
export function defaultDeviceLabel(ua: string): string {
  const browser = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Browser";
  const os = /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iOS" : /Windows/.test(ua) ? "Windows"
    : /Macintosh|Mac OS X/.test(ua) ? "macOS" : /Linux/.test(ua) ? "Linux" : "";
  return os ? `${browser} on ${os}` : browser;
}

/** The name given on Account & Security, else the default. */
export function getDeviceLabel(): string {
  let saved = "";
  try { saved = sanitiseDeviceLabel(localStorage.getItem(KEY) ?? ""); } catch { /* storage blocked: use the default */ }
  return saved || defaultDeviceLabel(typeof navigator === "undefined" ? "" : navigator.userAgent);
}

/** Saves the sanitised name (empty = back to the default) and returns what was saved. */
export function setDeviceLabel(label: string): string {
  const clean = sanitiseDeviceLabel(label);
  try { if (clean) localStorage.setItem(KEY, clean); else localStorage.removeItem(KEY); } catch { /* storage blocked */ }
  return clean;
}

export const deviceLabelHeader = (): Record<string, string> => ({ "X-Device-Label": getDeviceLabel() });
