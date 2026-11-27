<script lang="ts">
  import { sizeOf, setSize, clearSize } from "./panels.svelte";

  // A handle to drag a panel or column to a new size, kept between sessions under `id`. It sits in the gap between two
  // panels and sizes the one before it (`grow` 1: dragging right or down makes it bigger) or after it (`grow` -1).
  // Double-click (or Home) gives the panel back its own size; the arrow keys move it in steps.
  let {
    id,
    axis = "x",
    grow = 1,
    min = 160,
    max = Infinity,
    measure,
    label,
    edge,
    hideBelow = 0,
  }: {
    id: string;
    axis?: "x" | "y";
    grow?: 1 | -1;
    min?: number;
    /** The most it may take (px); a function is asked when the drag starts (e.g. what the window leaves). */
    max?: number | (() => number);
    /** The panel's size now (px), when the drag starts and it has none kept. */
    measure: () => number;
    /** What it resizes, for screen readers and the tooltip, e.g. "the parts list". */
    label: string;
    /**
     * Placed inside a column, over the gap on its end (right or bottom) or start (left) edge, rather than between two
     * panels: for grid pages. It still sizes what `measure` measures.
     */
    edge?: "end" | "start";
    /** Not shown on a window this narrow or narrower (where the page puts its columns one under the other). */
    hideBelow?: number;
  } = $props();

  let winW = $state(window.innerWidth);

  let dragging = $state(false);

  function limit(px: number): number {
    const top = typeof max === "function" ? max() : max;
    return Math.round(Math.max(min, Math.min(Math.max(min, top), px)));
  }

  function down(e: PointerEvent) {
    if (e.button !== 0) return;
    e.preventDefault();
    const handle = e.currentTarget as HTMLElement;
    handle.setPointerCapture(e.pointerId);
    const start = axis === "x" ? e.clientX : e.clientY;
    const from = sizeOf(id) ?? measure();
    dragging = true;
    const move = (m: PointerEvent) => {
      const d = (axis === "x" ? m.clientX : m.clientY) - start;
      setSize(id, limit(from + grow * d));
    };
    const up = () => {
      dragging = false;
      handle.removeEventListener("pointermove", move);
      handle.removeEventListener("pointerup", up);
      handle.removeEventListener("pointercancel", up);
    };
    handle.addEventListener("pointermove", move);
    handle.addEventListener("pointerup", up);
    handle.addEventListener("pointercancel", up);
  }

  function key(e: KeyboardEvent) {
    const step = e.shiftKey ? 64 : 16;
    const more = axis === "x" ? (grow > 0 ? "ArrowRight" : "ArrowLeft") : grow > 0 ? "ArrowDown" : "ArrowUp";
    const less = axis === "x" ? (grow > 0 ? "ArrowLeft" : "ArrowRight") : grow > 0 ? "ArrowUp" : "ArrowDown";
    if (e.key === more || e.key === less) {
      e.preventDefault();
      setSize(id, limit((sizeOf(id) ?? measure()) + (e.key === more ? step : -step)));
    } else if (e.key === "Home") {
      e.preventDefault();
      clearSize(id);
    }
  }
</script>

<svelte:window bind:innerWidth={winW} />

{#if winW > hideBelow}
<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<div
  class="splitter {axis}"
  class:dragging
  class:edge={!!edge}
  class:after={edge === "start"}
  role="separator"
  tabindex="0"
  aria-orientation={axis === "x" ? "vertical" : "horizontal"}
  aria-label="Resize {label}"
  aria-valuenow={sizeOf(id)}
  title="Drag to resize {label}; double-click for its own size"
  onpointerdown={down}
  ondblclick={() => clearSize(id)}
  onkeydown={key}
></div>
{#if dragging}<div class="shield {axis}"></div>{/if}
{/if}

<style>
  /* It fills the gap between the two panels (the negative margins take back the gap on each side). */
  .splitter {
    position: relative;
    flex: none;
    z-index: 2;
    touch-action: none;
  }
  .splitter.x {
    width: var(--gap);
    margin: 0 calc(-1 * var(--gap));
    align-self: stretch;
    cursor: col-resize;
  }
  .splitter.y {
    height: var(--gap);
    margin: calc(-1 * var(--gap)) 0;
    cursor: row-resize;
  }
  /* On the edge of its column: the gap after it (or before it, when it sizes the column after the gap). */
  .splitter.edge {
    position: absolute;
    margin: 0;
  }
  .splitter.edge.x {
    top: 0;
    bottom: 0;
    right: calc(-1 * var(--gap));
  }
  .splitter.edge.x.after {
    right: auto;
    left: calc(-1 * var(--gap));
  }
  .splitter.edge.y {
    left: 0;
    right: 0;
    bottom: calc(-1 * var(--gap));
  }
  /* A line in the middle of the gap when it is pointed at, dragged or focused. */
  .splitter::after {
    content: "";
    position: absolute;
    border-radius: 2px;
    background: transparent;
    transition: background 0.1s;
  }
  .splitter.x::after {
    top: 0;
    bottom: 0;
    left: calc(50% - 1.5px);
    width: 3px;
  }
  .splitter.y::after {
    left: 0;
    right: 0;
    top: calc(50% - 1.5px);
    height: 3px;
  }
  .splitter:hover::after,
  .splitter.dragging::after,
  .splitter:focus-visible::after {
    background: var(--select);
  }
  .splitter:focus-visible {
    outline: none;
  }
  /* While dragging, the pointer keeps its resize cursor over the map, plots and 3-D view, which would take it. */
  .shield {
    position: fixed;
    inset: 0;
    z-index: 1000;
  }
  .shield.x {
    cursor: col-resize;
  }
  .shield.y {
    cursor: row-resize;
  }
</style>
