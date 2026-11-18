<script lang="ts">
  import { tick } from "svelte";

  // A colour chooser drawn by the page: a swatch that opens a palette and a hex box. The browser's <input type=color>
  // opens the system's colour panel, which in the app's window on a Mac is the kind of native window that held the whole
  // app still (like the dropdown lists); this never leaves the page.
  let { value = "#ffffff", onchange, label = "Colour" }: { value?: string; onchange: (hex: string) => void; label?: string } = $props();

  const PALETTE = [
    "#ffffff", "#e6e6e6", "#bfbfbf", "#8c8c8c", "#595959", "#262626", "#000000",
    "#d32f2f", "#f4511e", "#fb8c00", "#fdd835", "#c0ca33", "#43a047", "#00897b",
    "#00acc1", "#1e88e5", "#3949ab", "#5e35b1", "#8e24aa", "#d81b60", "#6d4c41",
    "#ffcdd2", "#ffe0b2", "#fff9c4", "#c8e6c9", "#b3e5fc", "#c5cae9", "#f8bbd0",
  ];
  let open = $state(false);
  let btn: HTMLButtonElement | undefined = $state();
  let pop: HTMLDivElement | undefined = $state();
  let hex = $state("");
  let pos = $state({ left: 0, top: 0 });

  const norm = (v: string) => {
    const m = v.trim().replace(/^#?/, "#").toLowerCase();
    if (/^#[0-9a-f]{6}$/.test(m)) return m;
    if (/^#[0-9a-f]{3}$/.test(m)) return "#" + [...m.slice(1)].map((c) => c + c).join("");
    return null;
  };

  async function show() {
    hex = (norm(value ?? "") ?? "#ffffff").slice(1);
    open = true;
    await tick();
    const r = btn!.getBoundingClientRect();
    const w = pop?.offsetWidth ?? 240, h = pop?.offsetHeight ?? 200;
    const top = r.bottom + 4 + h > innerHeight - 8 ? r.top - 4 - h : r.bottom + 4;
    pos = { left: Math.max(8, Math.min(r.left, innerWidth - w - 8)), top: Math.max(8, top) };
    pop?.querySelector<HTMLButtonElement>("button.on, button")?.focus();
  }
  function pick(v: string) {
    open = false;
    btn?.focus();
    if (v !== norm(value ?? "")) onchange(v);
  }
  function typed() {
    const v = norm(hex);
    if (v) pick(v);
  }
  function outside(e: PointerEvent) {
    if (open && !pop?.contains(e.target as Node) && !btn?.contains(e.target as Node)) open = false;
  }
</script>

<svelte:window onpointerdown={outside} onresize={() => (open = false)} />

<button bind:this={btn} type="button" class="cswatch" style:background={value || "#ffffff"} aria-label={label} title={label} aria-haspopup="dialog" aria-expanded={open} onclick={() => (open ? (open = false) : show())}></button>
{#if open}
  <div bind:this={pop} class="cpop" role="dialog" tabindex="-1" aria-label={label} style:left="{pos.left}px" style:top="{pos.top}px"
    onkeydown={(e) => e.key === "Escape" && (e.stopPropagation(), (open = false), btn?.focus())}>
    <div class="grid">
      {#each PALETTE as c (c)}
        <button type="button" class:on={c === norm(value ?? "")} style:background={c} title={c} aria-label={c} onclick={() => pick(c)}></button>
      {/each}
    </div>
    <label class="hex">#<input class="input" bind:value={hex} maxlength="7" spellcheck="false" aria-label="Hex colour" onkeydown={(e) => e.key === "Enter" && typed()} onblur={typed} /></label>
  </div>
{/if}

<style>
  .cswatch {
    width: calc(34px * var(--text));
    height: calc(24px * var(--text));
    border: 1px solid var(--line-strong);
    border-radius: 5px;
    padding: 0;
    cursor: pointer;
    flex: none;
  }
  .cpop {
    position: fixed;
    z-index: 1000;
    padding: 8px;
    background: var(--surface);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius);
    box-shadow: var(--shadow-pop);
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(7, 24px);
    gap: 5px;
  }
  .grid button {
    width: 24px;
    height: 24px;
    border: 1px solid var(--line-strong);
    border-radius: 4px;
    padding: 0;
    cursor: pointer;
  }
  .grid button.on {
    outline: 2px solid var(--select);
    outline-offset: 1px;
  }
  .hex {
    display: flex;
    align-items: center;
    gap: 4px;
    font-family: ui-monospace, Menlo, Consolas, monospace;
    color: var(--ink-3);
  }
  .hex .input {
    flex: 1;
    min-width: 0;
    height: calc(28px * var(--text));
    font-family: inherit;
  }
</style>
