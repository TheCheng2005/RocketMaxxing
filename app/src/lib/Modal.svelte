<script lang="ts">
  import type { Snippet } from "svelte";
  import { X } from "@lucide/svelte";

  // A dialog over the page: a title bar, the content, closed with Escape, the cross or a click outside.
  let { title, close, width = 760, children }: { title: string; close: () => void; width?: number; children: Snippet } = $props();
</script>

<svelte:window onkeydown={(e) => e.key === "Escape" && close()} />

<div class="scrim" role="presentation" onclick={(e) => e.target === e.currentTarget && close()}>
  <div class="dlg" role="dialog" aria-label={title} style:width="min({width}px, calc(100vw - 32px))">
    <header>
      <h3>{title}</h3>
      <button class="btn ghost" onclick={close} aria-label="Close"><X size={16} /></button>
    </header>
    <div class="content">{@render children()}</div>
  </div>
</div>

<style>
  .scrim {
    position: fixed;
    inset: 0;
    background: rgb(0 0 0 / 30%);
    display: grid;
    place-items: center;
    z-index: 40;
  }
  .dlg {
    max-height: calc(100vh - 40px);
    display: flex;
    flex-direction: column;
    background: var(--surface);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius);
    box-shadow: var(--shadow-pop);
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 8px 8px 8px 16px;
    border-bottom: 1px solid var(--line);
  }
  h3 {
    margin: 0;
    font-size: calc(15px * var(--text));
  }
  .content {
    overflow: auto;
    padding: 12px 16px 16px;
  }
</style>
