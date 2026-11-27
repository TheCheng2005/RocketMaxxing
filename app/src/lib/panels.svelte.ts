// Which panels and sections are open, kept between sessions.

const KEY = "rocketmaxxing.panels";

function load(): Record<string, boolean> {
  try {
    return JSON.parse(localStorage.getItem(KEY) ?? "{}") ?? {};
  } catch {
    return {};
  }
}

const panels = $state<Record<string, boolean>>(load());

export function isOpen(id: string, initial = true): boolean {
  return panels[id] ?? initial;
}

export function toggle(id: string, initial = true) {
  setOpen(id, !isOpen(id, initial));
}

export function setOpen(id: string, open: boolean) {
  panels[id] = open;
  try {
    localStorage.setItem(KEY, JSON.stringify(panels));
  } catch {
    /* not kept */
  }
}

// Sizes the user dragged panels and columns to (px), kept between sessions; none until a handle is dragged.
const SIZES = "rocketmaxxing.panelSizes";

function loadSizes(): Record<string, number> {
  try {
    const v = JSON.parse(localStorage.getItem(SIZES) ?? "{}") ?? {};
    return Object.fromEntries(Object.entries(v).filter(([, n]) => typeof n === "number" && Number.isFinite(n) && n > 0)) as Record<string, number>;
  } catch {
    return {};
  }
}

const sizes = $state<Record<string, number>>(loadSizes());

function keepSizes() {
  try {
    localStorage.setItem(SIZES, JSON.stringify(sizes));
  } catch {
    /* not kept */
  }
}

/** The size a panel or column was dragged to (px), or undefined for its own. */
export function sizeOf(id: string): number | undefined {
  return sizes[id];
}

export function setSize(id: string, px: number) {
  sizes[id] = Math.round(px);
  keepSizes();
}

/** Back to the panel's own size (double-click on its handle). */
export function clearSize(id: string) {
  delete sizes[id];
  keepSizes();
}

/** Every panel and section back to how it starts, and every size (Settings > Reset the layout). */
export function resetPanels() {
  for (const k of Object.keys(panels)) delete panels[k];
  for (const k of Object.keys(sizes)) delete sizes[k];
  try {
    localStorage.removeItem(KEY);
    localStorage.removeItem(SIZES);
  } catch {
    /* nothing kept */
  }
}
