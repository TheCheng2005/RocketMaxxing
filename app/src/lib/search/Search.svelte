<script lang="ts">
  import { tick } from "svelte";
  import { Search } from "@lucide/svelte";
  import { entries } from "./entries";
  import { search, type Entry, type Kind } from "./search";
  import { ui } from "./ui.svelte";

  // Find anything by name or by what it is for: an analysis ("FEA", "chute size"), a page, an action, a part to add or
  // one in the design, a setting. Opened with the top bar's Search or ⌘K / Ctrl+K.
  const KIND: Record<Kind, string> = {
    analysis: "Analysis", page: "Page", action: "Action", add: "Add part", part: "In the design", property: "Part setting",
    standard: "Team standard", target: "Target", setting: "Setting",
  };
  // With nothing typed: the pages and the actions, then the analyses.
  const START: Kind[] = ["page", "action", "analysis"];

  let query = $state("");
  let at = $state(0);
  let input = $state<HTMLInputElement>();
  let list = $state<HTMLDivElement>();
  const all = entries();
  let found = $derived(
    query.trim() ? search(all, query, 40) : START.flatMap((k) => all.filter((e) => e.kind === k)).slice(0, 40),
  );

  $effect(() => {
    void found;
    at = 0;
  });
  $effect(() => {
    input?.focus();
  });

  function close() {
    ui.search = false;
  }

  function choose(e: Entry | undefined) {
    if (!e || e.off) return;
    close();
    e.run();
  }

  async function key(ev: KeyboardEvent) {
    if (ev.key === "ArrowDown" || ev.key === "ArrowUp") {
      ev.preventDefault();
      const n = found.length;
      if (!n) return;
      at = (at + (ev.key === "ArrowDown" ? 1 : n - 1)) % n;
      await tick();
      list?.querySelector(".hit.at")?.scrollIntoView({ block: "nearest" });
    } else if (ev.key === "Enter") {
      ev.preventDefault();
      ev.stopPropagation(); // what it opens (a tour) must not take this Enter as its own
      choose(found[at]);
    } else if (ev.key === "Escape") {
      ev.preventDefault();
      close();
    }
  }
</script>

<div class="scrim" role="presentation" onclick={(e) => e.target === e.currentTarget && close()}>
  <div class="box" role="dialog" aria-label="Search">
    <label class="q">
      <Search size={17} />
      <input bind:this={input} bind:value={query} onkeydown={key} placeholder="Search analyses, pages, parts and settings — e.g. FEA, chute, wind, dark"
        aria-label="Search" aria-controls="search-hits" spellcheck="false" autocomplete="off" />
    </label>
    <div class="hits" id="search-hits" role="listbox" bind:this={list}>
      {#each found as e, i (e)}
        <button class="hit" class:at={i === at} class:off={!!e.off} role="option" aria-selected={i === at} aria-disabled={!!e.off}
          title={e.off ?? ""} onmousemove={() => (at = i)} onclick={() => choose(e)}>
          <span class="kind k-{e.kind}">{KIND[e.kind]}</span>
          <span class="txt">
            <span class="t">{e.title}</span>
            {#if e.off}<span class="s">{e.off}</span>{:else if e.sub}<span class="s">{e.sub}</span>{/if}
          </span>
          {#if e.shortcut}<kbd>{e.shortcut}</kbd>{/if}
        </button>
      {:else}
        <p class="none">Nothing matches “{query}”. Try what it is for: stress, parachute, wind, motor, export.</p>
      {/each}
    </div>
    <div class="foot"><span><kbd>↑</kbd> <kbd>↓</kbd> move</span><span><kbd>Enter</kbd> open</span><span><kbd>Esc</kbd> close</span></div>
  </div>
</div>

<style>
  .scrim {
    position: fixed;
    inset: 0;
    z-index: 50;
    background: rgb(0 0 0 / 28%);
    display: flex;
    justify-content: center;
    align-items: flex-start;
    padding: 11vh 16px 16px;
  }
  .box {
    width: min(640px, 100%);
    max-height: 72vh;
    display: flex;
    flex-direction: column;
    background: var(--surface);
    border: 1px solid var(--line-strong);
    border-radius: 10px;
    box-shadow: var(--shadow-pop);
    overflow: hidden;
  }
  .q {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 14px;
    border-bottom: 1px solid var(--line);
    color: var(--ink-3);
  }
  .q input {
    flex: 1;
    min-width: 0;
    border: 0;
    outline: none;
    background: transparent;
    color: var(--ink);
    font: inherit;
    font-size: calc(15px * var(--text));
  }
  .hits {
    overflow: auto;
    padding: 4px;
  }
  .hit {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px 10px;
    border: 0;
    border-radius: 6px;
    background: transparent;
    color: var(--ink);
    text-align: left;
  }
  .hit.at {
    background: var(--select-soft);
  }
  .hit.off .t {
    color: var(--ink-3);
  }
  .kind {
    flex: none;
    width: 82px;
    font-size: calc(10.5px * var(--text));
    font-weight: 700;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    color: var(--ink-3);
  }
  .k-analysis {
    color: var(--select);
  }
  .k-add {
    color: var(--accent);
  }
  .txt {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
  }
  .t {
    font-weight: 500;
  }
  .s {
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  kbd {
    flex: none;
    font: inherit;
    font-size: calc(11px * var(--text));
    padding: 1px 5px;
    border: 1px solid var(--line);
    border-radius: 4px;
    color: var(--ink-3);
    background: var(--surface-2);
  }
  .none {
    margin: 10px;
    color: var(--ink-3);
  }
  .foot {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 6px 12px;
    border-top: 1px solid var(--line);
    font-size: calc(11.5px * var(--text));
    color: var(--ink-3);
  }
</style>
