// Settings that travel: in a RocketMaxxing project (the units and number format it was made with, so the team reads
// the same numbers) and in a settings file (everything a person chooses in Settings, to set up another computer or
// hand a team the same set-up). Recent files, panels and tours stay personal and are never in either.

import { prefs, setAccent, setDensity, setDigits, setMark, setMotion, setScheme, setSize, setTheme, SCHEMES, SIZES } from "./prefs.svelte";
import { units, choose, type Dim } from "./units.svelte";
import { autoCheck, setAutoCheck, setSystem } from "./state.svelte";

export interface Shared {
  app?: string;
  units?: { system: "metric" | "imperial"; chosen: Record<string, string> };
  digits?: number;
  mark?: "point" | "comma";
  theme?: "system" | "light" | "dark";
  scheme?: string;
  accent?: string;
  size?: number;
  density?: "compact" | "normal" | "comfortable";
  motion?: "system" | "reduce" | "full";
  autoCheck?: boolean;
  backupMinutes?: number;
}

/** What a project carries: how its numbers were read. */
export function projectSettings(): Shared {
  return { units: { system: units.system, chosen: { ...units.chosen } }, digits: prefs.digits, mark: prefs.mark };
}

/** Everything in Settings that is about how the app looks and reads, for a settings file. */
export function allSettings(): Shared {
  let backupMinutes = 5;
  try {
    const v = localStorage.getItem("rocketmaxxing.backupMinutes");
    if (v !== null) backupMinutes = Number(v);
  } catch {
    /* the default */
  }
  return {
    app: "RocketMaxxing settings",
    ...projectSettings(),
    theme: prefs.theme, scheme: prefs.scheme, accent: prefs.accent, size: prefs.size, density: prefs.density, motion: prefs.motion,
    autoCheck: autoCheck(), backupMinutes,
  };
}

/** In words: "imperial units, 5 figures, decimal comma". */
export function describe(s: Shared): string {
  const parts: string[] = [];
  if (s.units) parts.push(`${s.units.system} units${Object.keys(s.units.chosen ?? {}).length ? " (some changed)" : ""}`);
  if (s.digits) parts.push(`${s.digits} figures`);
  if (s.mark) parts.push(s.mark === "comma" ? "decimal comma" : "decimal point");
  return parts.join(", ");
}

/** Whether a project's units and number format are already the ones in use here. */
export function sameAsHere(s: Shared): boolean {
  const here = projectSettings();
  return JSON.stringify([s.units?.system, s.units?.chosen ?? {}, s.digits, s.mark]) === JSON.stringify([here.units!.system, here.units!.chosen, here.digits, here.mark]);
}

/**
 * Puts settings in use through the same setters Settings uses, taking only values it knows (a file from a newer app
 * or edited by hand cannot set anything else). Returns how many it applied.
 */
export async function applySettings(s: Shared): Promise<number> {
  let n = 0;
  if (s.units && (s.units.system === "metric" || s.units.system === "imperial")) {
    await setSystem(s.units.system);
    for (const [dim, unit] of Object.entries(s.units.chosen ?? {})) {
      if (typeof unit === "string" && units.dims[dim]?.units?.some((u) => u.unit === unit)) choose(dim as Dim, unit);
    }
    n++;
  }
  if (s.digits === 3 || s.digits === 4 || s.digits === 5) (setDigits(s.digits), n++);
  if (s.mark === "point" || s.mark === "comma") (setMark(s.mark), n++);
  if (s.theme === "system" || s.theme === "light" || s.theme === "dark") (setTheme(s.theme), n++);
  if (typeof s.scheme === "string" && SCHEMES.some((x) => x.id === s.scheme)) (setScheme(s.scheme), n++);
  if (typeof s.accent === "string") (setAccent(s.accent), n++);
  if (typeof s.size === "number" && SIZES.some(([v]) => v === s.size)) (setSize(s.size), n++);
  if (s.density === "compact" || s.density === "normal" || s.density === "comfortable") (setDensity(s.density), n++);
  if (s.motion === "system" || s.motion === "reduce" || s.motion === "full") (setMotion(s.motion), n++);
  if (typeof s.autoCheck === "boolean") (setAutoCheck(s.autoCheck), n++);
  if (typeof s.backupMinutes === "number" && [0, 2, 5, 10, 15].includes(s.backupMinutes)) {
    try {
      if (s.backupMinutes === 5) localStorage.removeItem("rocketmaxxing.backupMinutes");
      else localStorage.setItem("rocketmaxxing.backupMinutes", String(s.backupMinutes));
    } catch {
      /* stored by the engine through the preferences mirror, or not at all */
    }
    n++;
  }
  return n;
}
