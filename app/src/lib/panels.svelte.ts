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

/** Every panel and section back to how it starts (Settings > Reset the layout). */
export function resetPanels() {
  for (const k of Object.keys(panels)) delete panels[k];
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* nothing kept */
  }
}
