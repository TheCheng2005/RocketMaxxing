<script lang="ts">
  import type { Snippet } from "svelte";
  import { Info } from "@lucide/svelte";

  // An ⓘ that shows an explanation on hover or focus, and keeps it open when clicked (Escape or a click elsewhere
  // closes it). The popover is placed against the window, so a scrolling form does not clip it.
  let { label = "More information", wide = false, size = 13, children }: { label?: string; wide?: boolean; size?: number; children: Snippet } =
    $props();

  let btn = $state<HTMLButtonElement>();
  let pop = $state<HTMLDivElement>();
  let hover = $state(false);
  let pinned = $state(false);
  let open = $derived(hover || pinned);
  let pos = $state({ left: 0, top: 0 });

  $effect(() => {
    if (!open || !btn || !pop) return;
    const r = btn.getBoundingClientRect();
    const w = pop.offsetWidth, h = pop.offsetHeight;
    const left = Math.max(8, Math.min(r.left - 14, innerWidth - w - 8));
    const below = r.bottom + 6;
    pos = { left, top: below + h > innerHeight - 8 && r.top - h - 6 > 8 ? r.top - h - 6 : below };
  });

  function outside(e: MouseEvent) {
    if (pinned && !btn?.contains(e.target as Node) && !pop?.contains(e.target as Node)) pinned = false;
  }
</script>

<svelte:window onclick={outside} onkeydown={(e) => e.key === "Escape" && (pinned = hover = false)} />

<span class="tip" role="presentation" onmouseenter={() => (hover = true)} onmouseleave={() => (hover = false)}>
  <button bind:this={btn} type="button" class="i" class:on={open} aria-label={label} aria-expanded={open}
    onfocus={() => (hover = true)} onblur={() => (hover = false)}
    onclick={(e) => { e.preventDefault(); pinned = !pinned; }}><Info {size} /></button>
  {#if open}
    <div bind:this={pop} class="pop" class:wide role="tooltip" style="left: {pos.left}px; top: {pos.top}px">{@render children()}</div>
  {/if}
</span>

<style>
  .tip {
    display: inline-flex;
    vertical-align: middle;
  }
  .i {
    display: inline-grid;
    place-items: center;
    border: 0;
    background: transparent;
    padding: 1px;
    margin: 0 0 0 3px;
    color: var(--ink-3);
    border-radius: 50%;
    cursor: help;
  }
  .i:hover,
  .i.on {
    color: var(--select);
  }
  .pop {
    position: fixed;
    z-index: 60;
    width: max-content;
    max-width: min(340px, calc(100vw - 16px));
    padding: 8px 10px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface);
    color: var(--ink);
    box-shadow: 0 6px 22px rgba(0, 0, 0, 0.18);
    font-size: calc(12.5px * var(--text));
    font-weight: 400;
    line-height: 1.45;
    text-align: left;
    white-space: normal;
  }
  .pop.wide {
    max-width: min(560px, calc(100vw - 16px));
  }
</style>
