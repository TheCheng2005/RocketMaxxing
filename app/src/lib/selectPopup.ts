// Dropdown lists drawn by the page, for every <select> in the app.
//
// In the app's own window on a Mac, Chromium shows a <select>'s list as a native menu that runs its own modal loop on
// the main thread, the thread the window shares with Java, and the whole app froze with the list half open (1.6.0).
// So the native list is never opened: a click, or a key that would open it, opens this list instead, and choosing an
// item sets the <select> and fires input and change, as the browser would. The <select> itself stays (its value,
// focus, form behaviour and every binding are unchanged); only the list is ours. Elsewhere it behaves the same, so
// every computer gets the same list.

let open: { select: HTMLSelectElement; pop: HTMLDivElement; items: HTMLDivElement[]; active: number; done: () => void } | null = null;
let typed = "";
let typedAt = 0;

/** The entries to show: options in order, with group headings. */
function entries(select: HTMLSelectElement) {
  const out: ({ kind: "group"; label: string } | { kind: "option"; option: HTMLOptionElement })[] = [];
  for (const child of Array.from(select.children)) {
    if (child instanceof HTMLOptGroupElement) {
      out.push({ kind: "group", label: child.label });
      for (const o of Array.from(child.children)) if (o instanceof HTMLOptionElement && !o.hidden) out.push({ kind: "option", option: o });
    } else if (child instanceof HTMLOptionElement && !child.hidden) {
      out.push({ kind: "option", option: child });
    }
  }
  return out;
}

function usable(el: EventTarget | null): el is HTMLSelectElement {
  return el instanceof HTMLSelectElement && !el.disabled && !el.multiple && el.size <= 1;
}

function close(refocus = true) {
  if (!open) return;
  const { select, pop, done } = open;
  open = null;
  done();
  pop.remove();
  if (refocus) select.focus({ preventScroll: true });
}

function choose(option: HTMLOptionElement) {
  if (!open || option.disabled) return;
  const select = open.select;
  close();
  if (select.value === option.value && option.selected) return;
  option.selected = true;
  select.dispatchEvent(new Event("input", { bubbles: true }));
  select.dispatchEvent(new Event("change", { bubbles: true }));
}

function highlight(i: number) {
  if (!open) return;
  const n = open.items.length;
  if (!n) return;
  // skip disabled items in the direction of travel
  let k = Math.max(0, Math.min(n - 1, i));
  const dir = i >= open.active ? 1 : -1;
  for (let step = 0; step < n && open.items[k].getAttribute("aria-disabled") === "true"; step++) {
    k = Math.max(0, Math.min(n - 1, k + dir));
  }
  open.items[open.active]?.classList.remove("on");
  open.active = k;
  const el = open.items[k];
  el.classList.add("on");
  open.pop.setAttribute("aria-activedescendant", el.id);
  el.scrollIntoView({ block: "nearest" });
}

function place(select: HTMLSelectElement, pop: HTMLDivElement) {
  const r = select.getBoundingClientRect();
  const vw = window.innerWidth, vh = window.innerHeight;
  pop.style.minWidth = `${Math.round(r.width)}px`;
  pop.style.maxWidth = `${Math.max(r.width, Math.min(480, vw - 16))}px`;
  const below = vh - r.bottom - 8, above = r.top - 8;
  const want = Math.min(pop.scrollHeight, 360);
  const down = below >= Math.min(want, 200) || below >= above;
  const room = Math.max(120, down ? below : above);
  pop.style.maxHeight = `${Math.min(360, room)}px`;
  const h = Math.min(want, room);
  pop.style.top = `${Math.round(down ? r.bottom + 2 : r.top - 2 - h)}px`;
  const w = pop.offsetWidth;
  pop.style.left = `${Math.round(Math.max(8, Math.min(r.left, vw - w - 8)))}px`;
}

let uid = 0;
function show(select: HTMLSelectElement) {
  close(false);
  const pop = document.createElement("div");
  pop.className = "select-pop";
  pop.setAttribute("role", "listbox");
  pop.tabIndex = -1;
  if (select.getAttribute("aria-label")) pop.setAttribute("aria-label", select.getAttribute("aria-label")!);
  const items: HTMLDivElement[] = [];
  let active = -1;
  for (const e of entries(select)) {
    if (e.kind === "group") {
      const g = document.createElement("div");
      g.className = "select-pop-group";
      g.textContent = e.label;
      pop.append(g);
      continue;
    }
    const o = e.option;
    const it = document.createElement("div");
    it.className = "select-pop-item";
    it.id = `select-pop-${++uid}`;
    it.setAttribute("role", "option");
    it.textContent = o.label || o.text;
    if (o.title) it.title = o.title;
    if (o.disabled) it.setAttribute("aria-disabled", "true");
    if (o.selected) {
      it.setAttribute("aria-selected", "true");
      active = items.length;
    }
    // pointerdown, not click: the list must answer before anything else on the page sees the press
    it.addEventListener("pointerdown", (ev) => {
      ev.preventDefault();
      ev.stopPropagation();
    });
    it.addEventListener("click", (ev) => {
      ev.preventDefault();
      ev.stopPropagation();
      choose(o);
    });
    it.addEventListener("pointermove", () => {
      const k = items.indexOf(it);
      if (open && k !== open.active && !o.disabled) highlight(k);
    });
    items.push(it);
    pop.append(it);
  }
  document.body.append(pop);

  const onScroll = (ev: Event) => {
    if (ev.target instanceof Node && pop.contains(ev.target)) return;
    close(false);
  };
  const onResize = () => close(false);
  const onDown = (ev: PointerEvent) => {
    if (ev.target instanceof Node && (pop.contains(ev.target) || ev.target === select)) return;
    close(false);
  };
  const onBlur = () => close(false);
  window.addEventListener("scroll", onScroll, true);
  window.addEventListener("resize", onResize);
  window.addEventListener("blur", onBlur);
  document.addEventListener("pointerdown", onDown, true);
  const done = () => {
    window.removeEventListener("scroll", onScroll, true);
    window.removeEventListener("resize", onResize);
    window.removeEventListener("blur", onBlur);
    document.removeEventListener("pointerdown", onDown, true);
    select.removeAttribute("aria-expanded");
  };
  open = { select, pop, items, active: Math.max(0, active), done };
  select.setAttribute("aria-expanded", "true");
  place(select, pop);
  highlight(Math.max(0, active));
  select.focus({ preventScroll: true });
}

function onPointerDown(ev: PointerEvent) {
  if (ev.button !== 0 || !usable(ev.target)) return;
  ev.preventDefault(); // the native list never opens
  const select = ev.target;
  if (open?.select === select) close();
  else show(select);
}

function onMouseDown(ev: MouseEvent) {
  // Chromium opens the native list on mousedown too: stop that one as well
  if (ev.button === 0 && usable(ev.target)) ev.preventDefault();
}

function onKeyDown(ev: KeyboardEvent) {
  if (open) {
    const k = ev.key;
    if (k === "ArrowDown" || k === "ArrowUp" || k === "Home" || k === "End" || k === "PageDown" || k === "PageUp") {
      ev.preventDefault();
      const n = open.items.length;
      const step = k === "PageDown" ? 8 : k === "PageUp" ? -8 : k === "ArrowDown" ? 1 : -1;
      highlight(k === "Home" ? 0 : k === "End" ? n - 1 : open.active + step);
      return;
    }
    if (k === "Enter" || k === " ") {
      ev.preventDefault();
      const opts = entries(open.select).filter((e) => e.kind === "option") as { option: HTMLOptionElement }[];
      const o = opts[open.active]?.option;
      if (o) choose(o);
      else close();
      return;
    }
    if (k === "Escape") {
      ev.preventDefault();
      ev.stopPropagation();
      close();
      return;
    }
    if (k === "Tab") {
      close(false);
      return;
    }
    if (k.length === 1 && !ev.metaKey && !ev.ctrlKey && !ev.altKey) {
      ev.preventDefault();
      const now = Date.now();
      typed = (now - typedAt < 700 ? typed : "") + k.toLowerCase();
      typedAt = now;
      const k2 = open.items.findIndex((it) => (it.textContent ?? "").toLowerCase().startsWith(typed));
      if (k2 >= 0) highlight(k2);
    }
    return;
  }
  if (!usable(ev.target) || ev.metaKey || ev.ctrlKey) return;
  // the keys that open the native list on a Mac (arrows change the value elsewhere; ours opens the list everywhere)
  if (ev.key === " " || ev.key === "Enter" || ev.key === "ArrowDown" || ev.key === "ArrowUp" || (ev.altKey && ev.key === "ArrowDown")) {
    ev.preventDefault();
    show(ev.target);
  }
}

/** Once, at start: every <select> in the app uses the page's list from then on. */
export function installSelectPopup() {
  document.addEventListener("pointerdown", onPointerDown, true);
  document.addEventListener("mousedown", onMouseDown, true);
  document.addEventListener("keydown", onKeyDown, true);
}
