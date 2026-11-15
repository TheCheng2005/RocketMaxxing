// Units: the engine works in SI; the app shows and reads numbers in the unit chosen for each quantity
// (metric or imperial by default, any unit per quantity like OpenRocket's preferences).

import { prefs } from "./prefs.svelte";

export type Dim =
  | "LENGTH" | "DISTANCE" | "VELOCITY" | "ACCELERATION" | "MASS" | "FORCE" | "AREA" | "VOLUME" | "PRESSURE"
  | "DENSITY" | "TIME" | "ANGLE" | "IMPULSE" | "TEMPERATURE";

export interface UnitDef {
  unit: string;
  factor: number;
  offset: number;
}

interface DimDef {
  metric: string;
  imperial: string;
  units: UnitDef[];
}

const STORE = "rocketmaxxing.units";

function load(): { system: "metric" | "imperial"; chosen: Record<string, string> } {
  try {
    const s = JSON.parse(localStorage.getItem(STORE) ?? "");
    if (s && (s.system === "metric" || s.system === "imperial")) {
      return { system: s.system, chosen: s.chosen ?? {} };
    }
  } catch {
    /* first start, or storage unavailable */
  }
  return { system: "metric", chosen: {} };
}

export const units = $state({ ...load(), dims: {} as Record<string, DimDef> });

function save() {
  try {
    localStorage.setItem(STORE, JSON.stringify({ system: units.system, chosen: units.chosen }));
  } catch {
    /* not kept: fine */
  }
}

export function setSystem(system: "metric" | "imperial") {
  units.system = system;
  units.chosen = {};
  save();
}

export function choose(dim: Dim, unit: string) {
  units.chosen[dim] = unit;
  save();
}

export function unitOf(dim: Dim): UnitDef {
  const d = units.dims[dim];
  if (!d) {
    return { unit: "", factor: 1, offset: 0 };
  }
  const name = units.chosen[dim] ?? (units.system === "imperial" ? d.imperial : d.metric);
  return d.units.find((u) => u.unit === name) ?? d.units[0];
}

export function choices(dim: Dim): UnitDef[] {
  return units.dims[dim]?.units ?? [];
}

export function toShown(si: number, dim: Dim | undefined | null, unit?: UnitDef): number {
  if (!dim) return si;
  const u = unit ?? unitOf(dim);
  return (si - u.offset) / u.factor;
}

export function toSi(v: number, dim: Dim | undefined | null, unit?: UnitDef): number {
  if (!dim) return v;
  const u = unit ?? unitOf(dim);
  return v * u.factor + u.offset;
}

/** A number for reading: about four significant digits, no trailing zeros. */
/**
 * A number as the app writes it: {@code digits} significant figures (moved by the Settings choice, 3 to 5), grouped in
 * thousands, with the chosen decimal mark (1,234.5 or 1 234,5).
 */
export function num(v: number | null | undefined, digits = 4): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return "–";
  if (v === 0) return "0";
  const d = Math.max(2, digits + (prefs.digits - 4));
  const a = Math.abs(v);
  let t: string;
  if (a >= 10 ** d) t = Math.round(v).toLocaleString("en-US");
  else {
    const decimals = Math.max(0, d - 1 - Math.floor(Math.log10(a)));
    t = Number(v.toFixed(Math.min(decimals, 6))).toLocaleString("en-US", { maximumFractionDigits: 6 });
  }
  // A decimal comma groups thousands with a narrow space, so 1 234,5 cannot be read as two numbers.
  return prefs.mark === "comma" ? t.replace(/,/g, " ").replace(".", ",") : t;
}

/** A number for a box the team types into: no thousands grouping, the chosen decimal mark (30.5 or 30,5). */
export function editNum(v: number | null | undefined, digits = 4): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return "";
  return num(v, digits).replace(/[, ]/g, (c) => (c === "," && prefs.mark === "comma" ? "," : ""));
}

/**
 * A typed number made ready to read: with the decimal comma chosen, "1 234,5" or "1.234,5" is 1234.5; with the point,
 * "1,234.5" is 1234.5 and a lone comma with one, two or four or more digits after it ("30,5") is still a decimal comma.
 * Anything after the number (a unit: "3,5 in") is kept.
 */
export function readNum(text: string): string {
  const t = text.trim();
  const m = /^([-+]?[\d\s .,]*\d)(.*)$/s.exec(t);
  if (!m) return t;
  let n = m[1].replace(/[\s ]/g, "");
  if (prefs.mark === "comma") {
    n = n.includes(",") ? n.replace(/\./g, "").replace(",", ".") : n;
  } else if (/^[-+]?\d+,(\d{1,2}|\d{4,})$/.test(n)) {
    n = n.replace(",", ".");
  } else {
    n = n.replace(/,(?=\d{3}(\D|$))/g, "");
  }
  return n + m[2];
}

const PRETTY: Record<string, string> = { "m/s2": "m/s²", "ft/s2": "ft/s²", m2: "m²", cm2: "cm²", in2: "in²", ft2: "ft²",
  cm3: "cm³", in3: "in³", "kg/m3": "kg/m³", "g/cm3": "g/cm³", "lb/ft3": "lb/ft³", deg: "°", C: "°C", F: "°F", Ns: "N·s",
  "lbf-s": "lbf·s" };

export function pretty(unit: string): string {
  return PRETTY[unit] ?? unit;
}

/** "123.4 mm" in the chosen unit. */
export function fmt(si: number | null | undefined, dim: Dim | undefined | null, digits = 4): string {
  if (si === null || si === undefined || !Number.isFinite(si)) return "–";
  if (!dim) return num(si, digits);
  const u = unitOf(dim);
  const unit = pretty(u.unit);
  return num(toShown(si, dim, u), digits) + (unit === "°" ? "°" : " " + unit);
}

/** Number and unit apart, for big readouts: ["2,998", "m"]. */
export function split(si: number | null | undefined, dim: Dim | undefined | null, digits = 4): [string, string] {
  if (si === null || si === undefined || !Number.isFinite(si)) return ["–", ""];
  if (!dim) return [num(si, digits), ""];
  const u = unitOf(dim);
  return [num(toShown(si, dim, u), digits), pretty(u.unit)];
}
