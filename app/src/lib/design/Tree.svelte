<script lang="ts">
  import { app, select, parentOf } from "../state.svelte";
  import { rename } from "./partMenu";
  import TreeNode from "./TreeNode.svelte";
  import { tree, toggleFold, visibleRows } from "./treeState.svelte";

  let root = $derived(app.design?.tree);
  let host: HTMLDivElement;

  function focusRow(id: string) {
    requestAnimationFrame(() => (host?.querySelector(`[data-id="${id}"]`) as HTMLElement | null)?.focus());
  }

  // Arrow keys walk the list as in a file browser: up and down, right unfolds (or goes in), left folds (or goes out).
  function key(e: KeyboardEvent) {
    if (e.altKey || e.ctrlKey || e.metaKey || !root) return;
    const rows = visibleRows(root);
    const i = rows.findIndex((n) => n.id === app.selected);
    const cur = rows[i];
    let to: string | null = null;
    if (e.key === "ArrowDown") to = rows[Math.min(rows.length - 1, i + 1)]?.id;
    else if (e.key === "ArrowUp") to = rows[Math.max(0, i - 1)]?.id;
    else if (e.key === "Home") to = rows[0].id;
    else if (e.key === "End") to = rows[rows.length - 1].id;
    else if (e.key === "ArrowRight" && cur?.children.length) {
      if (tree.folded.has(cur.id)) toggleFold(cur.id);
      else to = cur.children[0].id;
    } else if (e.key === "ArrowLeft" && cur) {
      if (cur.children.length && !tree.folded.has(cur.id)) toggleFold(cur.id);
      else to = parentOf(root, cur.id)?.id ?? null;
    } else if (e.key === "Enter" && cur) {
      // Straight to the part's first setting.
      (document.querySelector('[data-panel="design.props"] .sections input, [data-panel="design.props"] .sections select') as HTMLElement | null)?.focus();
    } else if (e.key === "F2" && cur && cur.id !== "rocket") {
      rename();
    } else return;
    e.preventDefault();
    if (to && to !== app.selected) {
      select(to);
      focusRow(to);
    }
  }

</script>

<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<div class="tree" role="tree" tabindex="-1" aria-label="Parts" bind:this={host} onkeydown={key}>
  {#if root}
    <TreeNode node={root} depth={0} />
  {/if}
</div>
<div class="hint">Drag a part to move it, into a part or between two. Right-click for more.</div>

<style>
  .tree {
    flex: 1;
    overflow: auto;
    padding: 3px 0;
  }
  .hint {
    flex: none;
    padding: 4px 8px;
    border-top: 1px solid var(--line);
    font-size: calc(11.5px * var(--text));
    color: var(--ink-3);
  }
</style>
