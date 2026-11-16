// Appearance: light or dark (or the system's), and a colour scheme for the navigation rail, the top bar, the accent
// and the selection, after the way Slack lets people pick a theme. Kept between launches by the engine (storedPrefs.ts).

export type Theme = "system" | "light" | "dark";

export interface Scheme {
  id: string;
  name: string;
  /** Rail, accent and selection, for the swatch in Settings. */
  swatch: [string, string, string];
  /** For colour vision: also changes the pass / warn / fail colours. */
  vision?: string;
}

export const SCHEMES: Scheme[] = [
  { id: "launch", name: "Launch", swatch: ["#ffffff", "#d9480f", "#2563c9"] },
  { id: "nebula", name: "Nebula", swatch: ["#3b1840", "#8e3fa8", "#6d4bd1"] },
  { id: "afterburner", name: "Afterburner", swatch: ["#7c2d12", "#e8590c", "#2563c9"] },
  { id: "sunburst", name: "Sunburst", swatch: ["#fbe58a", "#b7791f", "#2563c9"] },
  { id: "aurora", name: "Aurora", swatch: ["#0f4a3c", "#12866a", "#2563c9"] },
  { id: "deepsea", name: "Deep sea", swatch: ["#0c3a5a", "#1479b8", "#2563c9"] },
  { id: "rose", name: "Rose", swatch: ["#5b1a36", "#c2255c", "#2563c9"] },
  { id: "midnight", name: "Midnight", swatch: ["#1c2558", "#3b5bdb", "#2563c9"] },
  { id: "graphite", name: "Graphite", swatch: ["#2e333a", "#4b5563", "#3b6aa8"] },
  { id: "redgreen", name: "Red–green safe", swatch: ["#1f3047", "#0072b2", "#d55e00"], vision: "Protanopia and deuteranopia: pass is bluish green, warnings orange-yellow, failures vermilion." },
  { id: "blueyellow", name: "Blue–yellow safe", swatch: ["#2b2b33", "#b8337a", "#0077bb"], vision: "Tritanopia: pass is blue, warnings orange, failures red." },
];

import { resetPanels } from "./panels.svelte";
import { setCommaMark } from "./numberMark";

const KEY = "rocketmaxxing.theme";
const SCHEME_KEY = "rocketmaxxing.scheme";

function read(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function load(): Theme {
  const t = read(KEY);
  return t === "light" || t === "dark" ? t : "system";
}

function loadScheme(): string {
  const s = read(SCHEME_KEY);
  return s && SCHEMES.some((x) => x.id === s) ? s : "launch";
}

/** How big text is drawn, as a factor of the normal size (buttons and boxes grow with it so it still fits). */
export const SIZES: [number, string][] = [[0.9, "Small"], [1, "Normal"], [1.12, "Large"], [1.25, "Larger"], [1.4, "Largest"]];
export type Density = "compact" | "normal" | "comfortable";
export type Motion = "system" | "reduce" | "full";
export type Mark = "point" | "comma";

const pick = <T extends string>(key: string, allowed: readonly T[], fallback: T): T => {
  const v = read(key) as T | null;
  return v && allowed.includes(v) ? v : fallback;
};
function loadSize(): number {
  const v = Number(read("rocketmaxxing.size"));
  return SIZES.some(([s]) => s === v) ? v : 1;
}
function loadDigits(): number {
  const v = Number(read("rocketmaxxing.digits"));
  return v === 3 || v === 5 ? v : 4;
}

export const prefs = $state({
  theme: load(),
  scheme: loadScheme(),
  size: loadSize(),
  density: pick<Density>("rocketmaxxing.density", ["compact", "normal", "comfortable"], "normal"),
  motion: pick<Motion>("rocketmaxxing.motion", ["system", "reduce", "full"], "system"),
  /** Significant figures in numbers the app writes (4 unless chosen). */
  digits: loadDigits(),
  /** The decimal mark in numbers shown and typed: 1,234.5 or 1 234,5. */
  mark: pick<Mark>("rocketmaxxing.mark", ["point", "comma"], "point"),
  /** A colour of the team's own for buttons and highlights, over the scheme's ("" for the scheme's). */
  accent: /^#[0-9a-f]{6}$/i.test(read("rocketmaxxing.accent") ?? "") ? read("rocketmaxxing.accent")! : "",
});

/** Black or white, whichever reads better on the colour. */
function inkOn(hex: string): string {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.4 ? "#111111" : "#ffffff";
}

setCommaMark(prefs.mark === "comma");

const motionQuery = typeof matchMedia === "function" ? matchMedia("(prefers-reduced-motion: reduce)") : null;

/** Whether animations are off: chosen in Settings, or asked for by the system. */
export function reducedMotion(): boolean {
  return prefs.motion === "reduce" || (prefs.motion === "system" && !!motionQuery?.matches);
}

const darkQuery = typeof matchMedia === "function" ? matchMedia("(prefers-color-scheme: dark)") : null;

export function applyTheme() {
  const root = document.documentElement;
  if (prefs.theme === "system") delete root.dataset.theme;
  else root.dataset.theme = prefs.theme;
  // The schemes' dark versions key on this, so they follow the system's choice too.
  const dark = prefs.theme === "dark" || (prefs.theme === "system" && !!darkQuery?.matches);
  if (dark) root.dataset.dark = "";
  else delete root.dataset.dark;
  if (prefs.scheme === "launch") delete root.dataset.scheme;
  else root.dataset.scheme = prefs.scheme;
  root.style.setProperty("--text", String(prefs.size));
  if (prefs.accent) {
    root.style.setProperty("--accent", prefs.accent);
    root.style.setProperty("--accent-ink", inkOn(prefs.accent));
    root.style.setProperty("--accent-soft", `color-mix(in srgb, ${prefs.accent} 16%, var(--surface))`);
  } else {
    for (const v of ["--accent", "--accent-ink", "--accent-soft"]) root.style.removeProperty(v);
  }
  if (prefs.density === "normal") delete root.dataset.density;
  else root.dataset.density = prefs.density;
  if (reducedMotion()) root.dataset.motion = "reduce";
  else delete root.dataset.motion;
}

motionQuery?.addEventListener("change", applyTheme);


darkQuery?.addEventListener("change", applyTheme);

function keep(key: string, value: string | null) {
  try {
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch {
    /* not kept */
  }
}

export function setTheme(t: Theme) {
  prefs.theme = t;
  keep(KEY, t === "system" ? null : t);
  applyTheme();
}

export function setScheme(id: string) {
  prefs.scheme = id;
  keep(SCHEME_KEY, id === "launch" ? null : id);
  applyTheme();
}

export function setSize(v: number) {
  prefs.size = v;
  keep("rocketmaxxing.size", v === 1 ? null : String(v));
  applyTheme();
  // Plots measure themselves when the window changes size: new text sizes change their axes' room too.
  dispatchEvent(new Event("resize"));
}

export function setDensity(d: Density) {
  prefs.density = d;
  keep("rocketmaxxing.density", d === "normal" ? null : d);
  applyTheme();
}

export function setMotion(m: Motion) {
  prefs.motion = m;
  keep("rocketmaxxing.motion", m === "system" ? null : m);
  applyTheme();
}

export function setDigits(n: number) {
  prefs.digits = n;
  keep("rocketmaxxing.digits", n === 4 ? null : String(n));
}

export function setMark(m: Mark) {
  prefs.mark = m;
  setCommaMark(m === "comma");
  keep("rocketmaxxing.mark", m === "point" ? null : m);
}

/** Forgets how the panels, plots' setup and 3-D view were left (not saved plots or anything about designs). */
export function resetLayout() {
  for (const k of ["rocketmaxxing.plotSetup", "rocketmaxxing.view"]) keep(k, null);
  resetPanels();
}

/** The first-visit tours show again on each page. */
export function resetTours() {
  keep("rocketmaxxing.tours", null);
}

/** A colour of the team's own over the scheme's accent, or "" to go back to the scheme's. */
export function setAccent(hex: string) {
  prefs.accent = /^#[0-9a-f]{6}$/i.test(hex) ? hex.toLowerCase() : "";
  keep("rocketmaxxing.accent", prefs.accent || null);
  applyTheme();
}
