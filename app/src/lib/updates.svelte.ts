// A newer RocketMaxxing on the Releases page: checked a few seconds after the app starts (the engine asks GitHub at
// most once a day), shown as a small card, or on demand from Help or Settings. Off with one switch in Settings.

import { call } from "./api";
import { toast } from "./state.svelte";

export interface UpdateInfo {
  current: string;
  latest?: string | null;
  newer?: boolean;
  url?: string;
  download?: string;
  file?: string;
  prerelease?: boolean;
  error?: string;
}

const OFF = "rocketmaxxing.updateCheck";
const SKIP = "rocketmaxxing.updateSkip";

export const updates = $state({ available: null as UpdateInfo | null, checking: false });

function read(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string | null) {
  try {
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch {
    /* storage unavailable */
  }
}

export function checkEnabled(): boolean {
  return read(OFF) !== "off";
}

export function setCheckEnabled(on: boolean) {
  write(OFF, on ? null : "off");
  if (!on) updates.available = null;
}

/** At start: quietly, and only for a version the user has not skipped. */
export function checkAtStart() {
  if (!checkEnabled()) return;
  setTimeout(async () => {
    try {
      const r = await call<UpdateInfo>("app_update_check", {});
      if (r.newer && r.latest && r.latest !== read(SKIP)) updates.available = r;
    } catch {
      /* no answer: nothing to say */
    }
  }, 4000);
}

/** From Help or Settings: always asks GitHub, and says what it found. */
export async function checkNow() {
  updates.checking = true;
  try {
    const r = await call<UpdateInfo>("app_update_check", { force: true });
    if (r.error) toast(r.error, "error");
    else if (r.newer) updates.available = r;
    else toast(`RocketMaxxing ${r.current} is the latest version.`);
  } catch (e: any) {
    toast(String(e?.message ?? e), "error");
  } finally {
    updates.checking = false;
  }
}

/** "Skip this version": no card for it again (a newer one still shows). */
export function skip() {
  if (updates.available?.latest) write(SKIP, updates.available.latest);
  updates.available = null;
}

export function later() {
  updates.available = null;
}
