<script lang="ts">
  import { tick } from "svelte";
  import { Check } from "@lucide/svelte";
  import { menu, closeMenu, type MenuItem } from "./menu.svelte";

  // The right-click menu on screen: kept inside the window, closed by a click elsewhere, Escape, scrolling or leaving
  // the window. The arrow keys move through it, Enter chooses.
  let box = $state<HTMLDivElement>();
  let pos = $state({ left: 0, top: 0 });

  $effect(() => {
    const m = menu.open;
    if (!m) return;
    pos = { left: m.x, top: m.y };
    tick().then(() => {
      if (!box) return;
      const w = box.offsetWidth, h = box.offsetHeight;
      pos = { left: Math.max(4, Math.min(m.x, innerWidth - w - 4)), top: Math.max(4, Math.min(m.y, innerHeight - h - 4)) };
      (box.querySelector("button:not(:disabled)") as HTMLElement | null)?.focus();
    });
  });

  function choose(it: MenuItem) {
    if (typeof it !== "object" || !("run" in it) || it.off) return;
    closeMenu();
    it.run();
  }

  function key(e: KeyboardEvent) {
    if (!menu.open) return;
    if (e.key === "Escape") {
      closeMenu();
      e.preventDefault();
      e.stopPropagation();
      return;
    }
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp" && e.key !== "Home" && e.key !== "End") return;
    e.preventDefault();
    e.stopPropagation();
    const items = [...(box?.querySelectorAll("button:not(:disabled)") ?? [])] as HTMLElement[];
    if (!items.length) return;
    const i = items.indexOf(document.activeElement as HTMLElement);
    const next = e.key === "Home" ? 0 : e.key === "End" ? items.length - 1 : e.key === "ArrowDown" ? (i + 1) % items.length : (i - 1 + items.length) % items.length;
    items[next].focus();
  }

  function outside(e: Event) {
    if (menu.open && !(e.target as HTMLElement).closest?.(".ctx")) closeMenu();
  }
</script>

<svelte:window onkeydowncapture={key} onpointerdown={outside} onblur={closeMenu} onresize={closeMenu} onwheel={(e) => menu.open && !(e.target as HTMLElement).closest?.(".ctx") && closeMenu()} />

{#if menu.open}
  <div class="ctx" role="menu" tabindex="-1" bind:this={box} style="left: {pos.left}px; top: {pos.top}px" oncontextmenu={(e) => e.preventDefault()}>
    {#each menu.open.items as it, i (i)}
      {#if it === "-"}
        <hr />
      {:else if "header" in it}
        <div class="head">{it.header}</div>
      {:else}
        <button role="menuitem" class:danger={it.danger} disabled={!!it.off} title={it.off || undefined} onclick={() => choose(it)}>
          <span class="mark">{#if it.checked}<Check size={13} />{/if}</span>
          <span class="label">{it.label}</span>
          {#if it.shortcut}<span class="key">{it.shortcut}</span>{/if}
        </button>
      {/if}
    {/each}
  </div>
{/if}

<style>
  .ctx {
    position: fixed;
    z-index: 90;
    min-width: 210px;
    max-width: 340px;
    max-height: calc(100vh - 8px);
    overflow: auto;
    padding: 4px;
    background: var(--surface);
    border: 1px solid var(--line-strong);
    border-radius: 8px;
    box-shadow: var(--shadow-pop);
    font-size: calc(13px * var(--text));
  }
  .head {
    padding: 4px 8px 3px 26px;
    font-size: calc(11.5px * var(--text));
    color: var(--ink-3);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  button {
    display: flex;
    align-items: center;
    width: 100%;
    gap: 4px;
    border: 0;
    background: transparent;
    padding: 5px 8px 5px 4px;
    border-radius: 5px;
    text-align: left;
    color: var(--ink);
  }
  .mark {
    width: 18px;
    flex: none;
    display: grid;
    place-items: center;
    color: var(--select);
  }
  .label {
    flex: 1;
    min-width: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .key {
    margin-left: 16px;
    color: var(--ink-3);
    font-size: calc(12px * var(--text));
    white-space: nowrap;
  }
  button:hover:not(:disabled),
  button:focus-visible {
    background: var(--select-soft);
    outline: none;
  }
  button:disabled {
    color: var(--ink-3);
    opacity: 0.6;
  }
  .danger:not(:disabled) {
    color: var(--bad);
  }
  hr {
    border: 0;
    border-top: 1px solid var(--line);
    margin: 4px 0;
  }
</style>
