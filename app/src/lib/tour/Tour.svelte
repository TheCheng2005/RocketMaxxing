<script lang="ts">
  import { tick } from "svelte";
  import { X } from "@lucide/svelte";
  import { tour, go, end } from "./session.svelte";

  // The tour on screen: the rest of the window dims, the part a step is about stays lit, and a card beside it says
  // what it is for. Back, Next, the arrow keys and Escape move through it; the page underneath waits until the end.
  let card = $state<HTMLDivElement>();
  let hole = $state<{ x: number; y: number; w: number; h: number } | null>(null);
  let pos = $state({ left: 0, top: 0 });
  let step = $derived(tour.steps[tour.at]);
  let last = $derived(tour.at === tour.steps.length - 1);

  const PAD = 6, GAP = 14, EDGE = 12;

  async function place() {
    await tick();
    await new Promise((r) => requestAnimationFrame(() => r(null)));
    const el = step?.target ? (document.querySelector(step.target) as HTMLElement | null) : null;
    if (el) {
      el.scrollIntoView({ block: "nearest", inline: "nearest" });
      const r = el.getBoundingClientRect();
      hole = { x: r.left - PAD, y: r.top - PAD, w: r.width + 2 * PAD, h: r.height + 2 * PAD };
    } else {
      hole = null;
    }
    await tick();
    const cw = card?.offsetWidth ?? 340, ch = card?.offsetHeight ?? 160;
    const W = innerWidth, H = innerHeight;
    const clampY = (y: number) => Math.max(EDGE, Math.min(y, H - ch - EDGE));
    const clampX = (x: number) => Math.max(EDGE, Math.min(x, W - cw - EDGE));
    if (!hole) {
      pos = { left: (W - cw) / 2, top: Math.max(EDGE, (H - ch) / 2.4) };
      return;
    }
    const h = hole;
    // Beside the lit part where there is room: right, left, below, above; else over its lower part.
    if (h.x + h.w + GAP + cw <= W - EDGE) pos = { left: h.x + h.w + GAP, top: clampY(h.y) };
    else if (h.x - GAP - cw >= EDGE) pos = { left: h.x - GAP - cw, top: clampY(h.y) };
    else if (h.y + h.h + GAP + ch <= H - EDGE) pos = { left: clampX(h.x), top: h.y + h.h + GAP };
    else if (h.y - GAP - ch >= EDGE) pos = { left: clampX(h.x), top: h.y - GAP - ch };
    else pos = { left: clampX(h.x + h.w / 2 - cw / 2), top: clampY(h.y + h.h - ch - GAP) };
  }

  $effect(() => {
    void tour.at;
    void tour.steps;
    place();
  });

  function key(e: KeyboardEvent) {
    if (!tour.name) return;
    if (e.key === "Escape") end();
    else if (e.key === "ArrowRight" || e.key === "Enter") go(tour.at + 1);
    else if (e.key === "ArrowLeft") go(Math.max(0, tour.at - 1));
    else return;
    e.preventDefault();
    e.stopPropagation();
  }
</script>

<svelte:window onkeydown={key} onresize={place} />

{#if step}
  <div class="veil" role="presentation">
    {#if hole}
      <div class="hole" style="left: {hole.x}px; top: {hole.y}px; width: {hole.w}px; height: {hole.h}px"></div>
    {:else}
      <div class="dim"></div>
    {/if}
    <div class="card" bind:this={card} role="dialog" aria-label={step.title} style="left: {pos.left}px; top: {pos.top}px">
      <header>
        <span class="count">{tour.at + 1} of {tour.steps.length}</span>
        <button class="x" onclick={end} aria-label="End the tour" title="End the tour (Esc)"><X size={15} /></button>
      </header>
      <h4>{step.title}</h4>
      <p>{step.text}</p>
      <div class="dots" aria-hidden="true">
        {#each tour.steps as _, i (i)}<span class:on={i === tour.at}></span>{/each}
      </div>
      <footer>
        {#if !last}<button class="skip" onclick={end}>Skip tour</button>{/if}
        <span class="sp"></span>
        {#if tour.at > 0}<button class="btn" onclick={() => go(tour.at - 1)}>Back</button>{/if}
        {#if last}
          {#each step.actions ?? [] as a (a.label)}<button class="btn" onclick={() => { end(); a.run(); }}>{a.label}</button>{/each}
          <button class="btn primary" onclick={end}>Done</button>
        {:else}
          <button class="btn primary" onclick={() => go(tour.at + 1)}>Next</button>
        {/if}
      </footer>
    </div>
  </div>
{/if}

<style>
  .veil {
    position: fixed;
    inset: 0;
    z-index: 70;
  }
  .dim {
    position: absolute;
    inset: 0;
    background: rgb(0 0 0 / 42%);
  }
  .hole {
    position: absolute;
    border-radius: 8px;
    box-shadow:
      0 0 0 3px var(--select),
      0 0 0 9999px rgb(0 0 0 / 42%);
    transition:
      left 0.18s ease,
      top 0.18s ease,
      width 0.18s ease,
      height 0.18s ease;
    pointer-events: none;
  }
  .card {
    position: absolute;
    width: min(340px, calc(100vw - 24px));
    padding: 10px 14px 12px;
    background: var(--surface);
    color: var(--ink);
    border: 1px solid var(--line-strong);
    border-radius: 10px;
    box-shadow: var(--shadow-pop);
    transition:
      left 0.18s ease,
      top 0.18s ease;
  }
  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .count {
    font-size: calc(11.5px * var(--text));
    color: var(--ink-3);
  }
  .x {
    display: grid;
    place-items: center;
    border: 0;
    background: transparent;
    color: var(--ink-3);
    padding: 2px;
    border-radius: 4px;
  }
  .x:hover {
    background: var(--surface-2);
    color: var(--ink);
  }
  h4 {
    margin: 2px 0 4px;
    font-size: calc(14.5px * var(--text));
  }
  p {
    margin: 0;
    line-height: 1.5;
    color: var(--ink-2);
  }
  .dots {
    display: flex;
    gap: 4px;
    margin: 10px 0 8px;
  }
  .dots span {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--line-strong);
  }
  .dots span.on {
    background: var(--select);
  }
  footer {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .sp {
    flex: 1;
  }
  .skip {
    border: 0;
    background: transparent;
    padding: 0;
    color: var(--ink-3);
    font-size: calc(12.5px * var(--text));
  }
  .skip:hover {
    color: var(--ink);
    text-decoration: underline;
  }
</style>
