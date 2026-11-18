// Right-click menus: one menu on screen at a time, with what makes sense where the click was. Each section of a page
// gives its own items (use:context); a click anywhere else gets the page's quick commands.

export type MenuItem =
  | {
      label: string;
      run: () => unknown;
      shortcut?: string;
      /** Why it cannot be used now (shown greyed out, with this as its tooltip); falsy: it can. */
      off?: string | false | null;
      danger?: boolean;
      checked?: boolean;
    }
  | { header: string }
  | "-";

export const menu = $state({
  open: null as null | { x: number; y: number; items: MenuItem[] },
});

/** Leaves out empty groups and doubled or trailing separators. */
function tidy(items: (MenuItem | null | undefined | false)[]): MenuItem[] {
  const out: MenuItem[] = [];
  for (const it of items) {
    if (!it) continue;
    if (it === "-" && (out.length === 0 || out[out.length - 1] === "-")) continue;
    out.push(it);
  }
  while (out.length && (out[out.length - 1] === "-" || typeof out[out.length - 1] === "object" && "header" in (out[out.length - 1] as object))) out.pop();
  return out;
}

export function openMenu(x: number, y: number, items: (MenuItem | null | undefined | false)[]) {
  const list = tidy(items);
  menu.open = list.length ? { x, y, items: list } : null;
}

export function closeMenu() {
  menu.open = null;
}

const TEXT_BOX = "input:not([type=checkbox]):not([type=radio]):not([type=range]):not([type=button]), textarea, [contenteditable=true]";

/** Where a right-click is about text: a text box, or text the user has selected. These get the text menu below. */
export function nativeHere(e: MouseEvent): boolean {
  const t = e.target as HTMLElement;
  if (t.closest?.(TEXT_BOX)) return true;
  return !!String(getSelection() ?? "").trim();
}

/**
 * Cut, copy, paste and select all, for a right-click on text. The browser's own menu is never shown: in the app's
 * window on a Mac it is a native menu that held the whole app still (like the dropdown lists). The window does the
 * editing (it can read the clipboard for paste); in a browser tab the page does what the browser allows.
 */
export function openTextMenu(e: MouseEvent) {
  const box = (e.target as HTMLElement).closest?.(TEXT_BOX) as HTMLInputElement | HTMLTextAreaElement | HTMLElement | null;
  const field = box && "selectionStart" in box ? (box as HTMLInputElement) : null;
  const start = field?.selectionStart ?? null, end = field?.selectionEnd ?? null;
  const selected = field ? (start !== null && end !== null && end > start) : !!String(getSelection() ?? "").trim();
  const editable = !!box && !(field?.readOnly || (field as HTMLInputElement | null)?.disabled);
  const back = () => {
    if (!box) return;
    box.focus({ preventScroll: true });
    if (field && start !== null && end !== null) {
      try {
        field.setSelectionRange(start, end);
      } catch {
        /* a number box has no selection to restore */
      }
    }
  };
  const run = (action: "cut" | "copy" | "paste" | "selectAll") => async () => {
    back();
    const done = await import("../api").then(({ call }) => call<{ done: boolean }>("app_window_edit", { action })).then((r) => r.done).catch(() => false);
    if (done) return;
    // no app window: the page's own way
    if (action === "paste") {
      const text = await navigator.clipboard?.readText?.().catch(() => "");
      back();
      if (text) document.execCommand("insertText", false, text);
    } else if (action === "selectAll") {
      if (field) field.select();
      else document.execCommand("selectAll");
    } else {
      document.execCommand(action);
    }
  };
  openMenu(e.clientX, e.clientY, [
    editable && { label: "Cut", shortcut: keyLabel("X"), off: selected ? false : "Select some text first", run: run("cut") },
    { label: "Copy", shortcut: keyLabel("C"), off: selected ? false : "Select some text first", run: run("copy") },
    editable && { label: "Paste", shortcut: keyLabel("V"), run: run("paste") },
    "-",
    box && { label: "Select all", shortcut: keyLabel("A"), run: run("selectAll") },
  ]);
}

const keyLabel = (k: string) => (typeof navigator !== "undefined" && /Mac/.test(navigator.platform || navigator.userAgent) ? "⌘" : "Ctrl+") + k;

/**
 * A section's own right-click menu: {@code use:context={() => [...items]}}. The items are made at the click, so they
 * follow the state of the moment. Text boxes keep the browser's menu (copy, paste).
 */
export function context(node: HTMLElement, items: (e: MouseEvent) => (MenuItem | null | undefined | false)[]) {
  let get = items;
  const on = (e: MouseEvent) => {
    if (nativeHere(e)) {
      e.preventDefault();
      e.stopPropagation();
      openTextMenu(e);
      return;
    }
    e.preventDefault();
    e.stopPropagation();
    openMenu(e.clientX, e.clientY, get(e));
  };
  node.addEventListener("contextmenu", on);
  return {
    update(next: typeof items) {
      get = next;
    },
    destroy() {
      node.removeEventListener("contextmenu", on);
    },
  };
}

type Items = () => (MenuItem | null | undefined | false)[];
let pageItems: Items | null = null;

/** A page's quick commands, for a right-click anywhere on it that has no menu of its own (call in the page's script). */
export function pageMenu(items: Items) {
  $effect(() => {
    pageItems = items;
    return () => {
      if (pageItems === items) pageItems = null;
    };
  });
}

export function currentPageItems(): (MenuItem | null | undefined | false)[] {
  return pageItems?.() ?? [];
}

/** Puts text on the clipboard (the window's own way first, the old way where that is not allowed). */
export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    const t = document.createElement("textarea");
    t.value = text;
    t.style.position = "fixed";
    t.style.opacity = "0";
    document.body.appendChild(t);
    t.select();
    const ok = document.execCommand("copy");
    t.remove();
    return ok;
  }
}
