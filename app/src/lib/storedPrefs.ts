// What the interface remembers between launches (recent designs, colour scheme, units, panels, tours seen). The page's
// own storage belongs to its address, and the app's port changes every launch, so the engine keeps these in a file
// (app_prefs) and the page copies them into its storage before anything reads it, then sends later changes back.

import { call, callOnExit, connected } from "./api";

const PREFIX = "rocketmaxxing.";
/** Kept for this run only: the page shown last (to come back to after a reload). */
const THIS_RUN = new Set(["rocketmaxxing.page", "rocketmaxxing.token"]);
const kept = (key: string) => key.startsWith(PREFIX) && !THIS_RUN.has(key);

let pending: Record<string, string | null> = {};
let timer: ReturnType<typeof setTimeout> | undefined;

function send(leaving = false) {
  clearTimeout(timer);
  const set = pending;
  pending = {};
  if (!Object.keys(set).length) return;
  // A change made just before the window closes still has to arrive: an ordinary request dies with the page.
  if (leaving) callOnExit("app_prefs", { set });
  else call("app_prefs", { set }).catch(() => {});
}

function queue(key: string, value: string | null) {
  pending[key] = value;
  clearTimeout(timer);
  timer = setTimeout(() => send(), 300);
}

/** Loads the stored preferences into the page's storage and mirrors later changes to the engine. Never throws. */
export async function loadStoredPrefs(): Promise<void> {
  if (!connected) return;
  let stored: Record<string, string>;
  try {
    stored = (await call<{ prefs: Record<string, string> }>("app_prefs", {})).prefs ?? {};
  } catch {
    return; // an older engine, or none: the page's own storage, as before
  }
  try {
    for (const [k, v] of Object.entries(stored)) if (kept(k)) localStorage.setItem(k, v);
    const proto = Storage.prototype;
    const setItem = proto.setItem, removeItem = proto.removeItem;
    proto.setItem = function (this: Storage, key: string, value: string) {
      setItem.call(this, key, value);
      if (this === localStorage && kept(key)) queue(key, String(value));
    };
    proto.removeItem = function (this: Storage, key: string) {
      removeItem.call(this, key);
      if (this === localStorage && kept(key)) queue(key, null);
    };
    addEventListener("pagehide", () => send(true));
  } catch {
    /* storage unavailable: nothing to mirror */
  }
}
