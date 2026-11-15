<script lang="ts">
  import { ChevronRight, Flame, Scale } from "@lucide/svelte";
  import { app, select, moveTo, type TreeNode as Node } from "../state.svelte";
  import ComponentIcon from "../ComponentIcon.svelte";
  import Self from "./TreeNode.svelte";
  import { tree, toggleFold, zoneAt, dropTarget } from "./treeState.svelte";
  import { openMenu } from "../menu/menu.svelte";
  import { partItems } from "./partMenu";

  let { node, depth }: { node: Node; depth: number } = $props();
  let open = $derived(!tree.folded.has(node.id));
  let row: HTMLDivElement | undefined = $state();
  let selected = $derived(app.selected === node.id);
  let previewed = $derived(app.hover === node.id && !selected);
  let zone = $derived(tree.drop?.id === node.id ? tree.drop.zone : null);
  let dragged = $derived(tree.dragging?.id === node.id);
  // A new part's row flashes once; restarting the animation needs the class off for a frame.
  let flashing = $state(false);
  $effect(() => {
    const f = app.flash;
    if (!f || f.id !== node.id || !f.added) return;
    flashing = false;
    requestAnimationFrame(() => (flashing = true));
    const t = setTimeout(() => (flashing = false), 1300);
    return () => clearTimeout(t);
  });

  $effect(() => {
    if (selected) row?.scrollIntoView({ block: "nearest" });
  });

  // Dragging rows: before or after a row (its top or bottom quarter), or into it (the middle). A folded row held
  // over for a moment unfolds, so a part can go deep without unfolding first.
  let unfoldTimer: ReturnType<typeof setTimeout> | undefined;
  function start(e: DragEvent) {
    if (node.id === "rocket") return;
    tree.dragging = { id: node.id, type: node.type };
    e.dataTransfer?.setData("text/plain", node.name);
    if (e.dataTransfer) e.dataTransfer.effectAllowed = "move";
  }
  function over(e: DragEvent) {
    if (!tree.dragging || !row) return;
    const r = row.getBoundingClientRect();
    const z = zoneAt(node, (e.clientY - r.top) / r.height);
    if (!z) {
      if (tree.drop?.id === node.id) tree.drop = null;
      return;
    }
    e.preventDefault();
    if (e.dataTransfer) e.dataTransfer.dropEffect = "move";
    if (tree.drop?.id !== node.id || tree.drop.zone !== z) {
      tree.drop = { id: node.id, zone: z };
      // The view marks where it would go: the part it would go into.
      const t = dropTarget(node.id, z);
      app.hover = t ? t[0] : null;
    }
    if (z === "into" && !open && node.children.length && !unfoldTimer) {
      unfoldTimer = setTimeout(() => {
        if (tree.drop?.id === node.id && tree.folded.has(node.id)) toggleFold(node.id);
        unfoldTimer = undefined;
      }, 650);
    }
  }
  function leave(e: DragEvent) {
    if (row && e.relatedTarget instanceof globalThis.Node && row.contains(e.relatedTarget)) return;
    clearTimeout(unfoldTimer);
    unfoldTimer = undefined;
    if (tree.drop?.id === node.id) tree.drop = null;
  }
  function drop(e: DragEvent) {
    e.preventDefault();
    const d = tree.dragging, z = tree.drop?.id === node.id ? tree.drop.zone : null;
    end();
    if (!d || !z) return;
    const t = dropTarget(node.id, z);
    if (t) moveTo(d.id, t[0], t[1]);
  }
  function end() {
    clearTimeout(unfoldTimer);
    unfoldTimer = undefined;
    tree.dragging = null;
    tree.drop = null;
    app.hover = null;
  }
  function menu(e: MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    const items = partItems(node.id);
    const fold = node.children.length
      ? [{ label: open ? "Fold" : "Unfold", run: () => toggleFold(node.id) }, { label: "Fold everything inside", run: () => foldAll(node, true) }, { label: "Unfold everything inside", run: () => foldAll(node, false) }]
      : [];
    openMenu(e.clientX, e.clientY, [...items.slice(0, -2), "-", ...fold, "-", ...items.slice(-2)]);
  }
  function foldAll(n: Node, shut: boolean) {
    const f = new Set(tree.folded);
    const walk = (x: Node) => {
      if (x.children.length) shut ? f.add(x.id) : f.delete(x.id);
      x.children.forEach(walk);
    };
    walk(n);
    if (shut) f.delete(n.id);
    tree.folded = f;
  }
</script>

<!-- svelte-ignore a11y_click_events_have_key_events (the list's arrow keys and Enter are handled in Tree.svelte) -->
<div
  bind:this={row}
  class="row"
  class:sel={selected}
  class:hov={previewed}
  class:flash={flashing}
  class:dragged
  class:before={zone === "before"}
  class:after={zone === "after"}
  class:into={zone === "into"}
  data-id={node.id}
  draggable={node.id !== "rocket"}
  ondragstart={start}
  ondragover={over}
  ondragleave={leave}
  ondrop={drop}
  ondragend={end}
  oncontextmenu={menu}
  onmouseenter={() => !tree.dragging && (app.hover = node.id)}
  onmouseleave={() => !tree.dragging && app.hover === node.id && (app.hover = null)}
  style="padding-left: {4 + depth * 14}px"
  role="treeitem"
  aria-selected={selected}
  aria-expanded={node.children.length ? open : undefined}
  aria-level={depth + 1}
  tabindex={selected ? 0 : -1}
  onclick={() => select(node.id)}
>
  {#if node.children.length}
    <button class="tw" class:open tabindex="-1" onclick={(e) => (e.stopPropagation(), toggleFold(node.id))} aria-label={open ? "Collapse" : "Expand"}>
      <ChevronRight size={14} />
    </button>
  {:else}
    <span class="tw"></span>
  {/if}
  <ComponentIcon type={node.type} size={24} />
  <span class="name" title="{node.name} ({node.label})">{node.name}</span>
  {#if node.motorMount}<span class="badge mount" title="Motor mount"><Flame size={12} /></span>{/if}
  {#if node.overridden}<span class="badge ovr" title="Mass or CG overridden"><Scale size={12} /></span>{/if}
</div>
{#if open}
  {#each node.children as c (c.id)}
    <Self node={c} depth={depth + 1} />
  {/each}
{/if}

<style>
  .row {
    position: relative;
    display: flex;
    align-items: center;
    gap: 5px;
    height: var(--control);
    padding-right: 6px;
    cursor: default;
    user-select: none;
  }
  .row:hover {
    background: var(--surface-2);
  }
  .row:focus-visible {
    outline: 2px solid var(--select);
    outline-offset: -2px;
  }
  .row.sel {
    background: var(--select-soft);
    box-shadow: inset 3px 0 0 var(--select);
    font-weight: 600;
  }
  .row.hov {
    box-shadow: inset 0 0 0 1px var(--select);
  }
  .row.dragged {
    opacity: 0.45;
  }
  .row.before::before,
  .row.after::after {
    content: "";
    position: absolute;
    left: 0;
    right: 0;
    height: 3px;
    border-radius: 2px;
    background: var(--select);
    pointer-events: none;
  }
  .row.before::before {
    top: -1px;
  }
  .row.after::after {
    bottom: -1px;
  }
  .row.into {
    background: var(--select-soft);
    box-shadow: inset 0 0 0 2px var(--select);
  }
  .row.flash {
    animation: added 1.2s ease-out;
  }
  @keyframes added {
    0%,
    25% {
      background: color-mix(in srgb, var(--select) 45%, var(--surface));
    }
    100% {
      background: var(--select-soft);
    }
  }
  .tw {
    width: 16px;
    height: 16px;
    flex: none;
    display: grid;
    place-items: center;
    border: 0;
    padding: 0;
    background: transparent;
    color: var(--ink-3);
    transition: transform 0.1s;
  }
  .tw.open {
    transform: rotate(90deg);
  }
  .name {
    flex: 1;
    min-width: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .badge {
    display: grid;
    place-items: center;
    width: 18px;
    height: 18px;
    flex: none;
  }
  .mount {
    color: var(--accent);
  }
  .ovr {
    color: var(--warn);
  }
</style>
