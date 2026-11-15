<script lang="ts">
  import type { Component, Snippet } from "svelte";
  import { ChevronDown, PanelLeftClose, PanelRightClose } from "@lucide/svelte";
  import { isOpen, toggle, setOpen } from "./panels.svelte";
  import { context, currentPageItems, type MenuItem } from "./menu/menu.svelte";

  // A titled panel that folds away. Side panels fold to a narrow strip so the view gets the width.
  let {
    id,
    title,
    icon: Icon,
    side,
    initial = true,
    fill = false,
    tools,
    status,
    children,
  }: {
    id: string;
    title: string;
    /** A lucide icon beside the title. */
    icon?: Component<{ size?: number }>;
    side?: "left" | "right";
    initial?: boolean;
    fill?: boolean;
    tools?: Snippet;
    status?: Snippet;
    children: Snippet;
  } = $props();

  let open = $derived(isOpen(id, initial));

  // Right-click on the title: fold this panel or the others, then the page's quick commands.
  function others(): string[] {
    return [...document.querySelectorAll("[data-panel]")].map((e) => (e as HTMLElement).dataset.panel!).filter((p) => p !== id);
  }
  function menuItems(): MenuItem[] {
    return [
      { header: title },
      { label: open ? (side ? "Hide" : "Fold") : "Show", run: () => toggle(id, initial) },
      { label: "Fold the other panels", run: () => others().forEach((p) => setOpen(p, false)), off: others().length ? false : "No other panel here" },
      { label: "Unfold every panel", run: () => [id, ...others()].forEach((p) => setOpen(p, true)) },
      "-",
      ...(currentPageItems() as MenuItem[]),
    ];
  }
</script>

{#if side && !open}
  <button class="strip {side}" data-panel={id} use:context={menuItems} onclick={() => toggle(id, initial)} title="Show {title.toLowerCase()}">
    <span>{title}</span>
  </button>
{:else}
  <section class="panel" class:fill={fill && open} class:side data-panel={id}>
    <header use:context={menuItems}>
      {#if side}
        <span class="title">{#if Icon}<span class="ico"><Icon size={15} /></span>{/if}{title}</span>
      {:else}
        <button class="title fold" onclick={() => toggle(id, initial)} aria-expanded={open}>
          {#if Icon}<span class="ico"><Icon size={15} /></span>{/if}
          {title}
          <span class="chev" class:shut={!open}><ChevronDown size={14} /></span>
        </button>
      {/if}
      <div class="tools">
        {#if status}{@render status()}{/if}
        {#if open && tools}{@render tools()}{/if}
        {#if side}
          <button class="hide" onclick={() => toggle(id, initial)} title="Hide {title.toLowerCase()}">
            {#if side === "left"}<PanelLeftClose size={15} />{:else}<PanelRightClose size={15} />{/if}
          </button>
        {/if}
      </div>
    </header>
    {#if open}
      <div class="body">{@render children()}</div>
    {/if}
  </section>
{/if}

<style>
  .panel {
    display: flex;
    flex-direction: column;
    min-height: 0;
    min-width: 0;
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: 8px;
    box-shadow: var(--shadow);
    overflow: hidden;
  }
  .panel.fill {
    flex: 1;
  }
  .panel.side {
    height: 100%;
  }
  header {
    flex: none;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    height: 40px;
    padding: 0 8px 0 14px;
    background: var(--surface);
    border-bottom: 1px solid var(--line);
  }
  .title {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;
    font-size: calc(13.5px * var(--text));
    color: var(--ink);
    white-space: nowrap;
    min-width: 0;
  }
  .ico {
    display: grid;
    place-items: center;
    width: 26px;
    height: 26px;
    flex: none;
    border-radius: 7px;
    background: var(--select-soft);
    color: var(--select);
  }
  .fold {
    border: 0;
    background: transparent;
    padding: 0;
    height: 100%;
  }
  .fold:hover {
    color: var(--ink);
  }
  .chev {
    display: flex;
    color: var(--ink-3);
    transition: transform 0.1s;
  }
  .chev.shut {
    transform: rotate(-90deg);
  }
  .tools {
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
  }
  .hide {
    display: grid;
    place-items: center;
    width: 28px;
    height: 28px;
    border: 0;
    border-radius: 6px;
    background: transparent;
    color: var(--ink-3);
  }
  .hide:hover {
    background: var(--surface-3);
    color: var(--ink);
  }
  .body {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    overflow: auto;
  }
  .strip {
    flex: none;
    width: 34px;
    height: 100%;
    border: 1px solid var(--line);
    border-radius: 8px;
    background: var(--surface);
    color: var(--ink-2);
    font-weight: 600;
    font-size: calc(12.5px * var(--text));
    padding: 10px 0;
    display: flex;
    justify-content: center;
  }
  .strip:hover {
    background: var(--surface-3);
    color: var(--ink);
  }
  .strip span {
    writing-mode: vertical-rl;
  }
  .strip.left span {
    transform: rotate(180deg);
  }
</style>
