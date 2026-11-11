// Labels for engine field names, and how to read result values.

const WORDS: Record<string, string> = {
  meop: "MEOP", cd: "Cd", cds: "Drag coefficients", cg: "CG", cp: "CP", agl: "AGL", msl: "MSL", mhz: "(MHz)",
  dbm: "(dBm)", dbi: "(dBi)", db: "(dB)", sd: "std. dev.", rx: "receiver", tx: "transmitter", machs: "Mach numbers",
  kml: "KML", csv: "CSV", cfd: "CFD", fin: "fin", id: "ID", aoa: "AoA", cx: "Cx", cn: "CN", ld: "L/D",
  cstar: "c*", url: "URL", glpp: "GLPP", isp: "Isp", aasi: "AASI", gps: "GPS", json: "JSON", fea: "FEA",
};

/** "railExitVelocity" -> "Rail exit velocity"; "txPowerDbm" -> "Transmitter power (dBm)". */
export function label(key: string): string {
  const parts = key
    .replace(/\(.*?\)/g, (m) => " " + m)
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/([A-Z]+)([A-Z][a-z])/g, "$1 $2")
    .split(/\s+/)
    .filter(Boolean)
    .map((w) => WORDS[w.toLowerCase()] ?? w.toLowerCase());
  const s = parts.join(" ");
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** First sentence of an engine description (they are written for a model, and long). */
export function firstSentence(text: string | undefined): string {
  if (!text) return "";
  const m = text.match(/^(.+?[.!?])(\s|$)/);
  return (m ? m[1] : text).trim();
}

export function isPrimitive(v: unknown): boolean {
  return v === null || ["string", "number", "boolean"].includes(typeof v);
}

/** "pass", "warn", "fail" or "" from a status-like value ("PASS", "WARN: below the margin", "OK"). */
export function status(v: unknown): "pass" | "warn" | "fail" | "" {
  if (typeof v !== "string") return "";
  const s = v.trim().toUpperCase();
  if (/^(PASS|OK)\b/.test(s)) return "pass";
  if (/^(WARN|WARNING|TODO|CHECK)\b/.test(s)) return "warn";
  if (/^(FAIL|FAILED|ERROR)\b/.test(s)) return "fail";
  return "";
}

/** A file the engine wrote, worth a "Show" button. */
export function isPath(key: string, v: unknown): v is string {
  return typeof v === "string" && /^(\/|[A-Za-z]:\\)/.test(v) && /(file|path|plot|report|template|dir|csv|kml|card|saved|written)/i.test(key);
}
