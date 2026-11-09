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

export const prefs = $state({ theme: load(), scheme: loadScheme() });

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
}

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
