<script lang="ts">
  import { Plus, RotateCcw, Trash2 } from "@lucide/svelte";
  import { label } from "../analysis/text";
  import StdNode from "./StdNode.svelte";
  import { NAMED, at, isNote, isObject, parse, type Json } from "./std";
  import Tip from "../Tip.svelte";
  import { STD_HELP } from "../guide/content";
  import { context, copyText, currentPageItems, type MenuItem } from "../menu/menu.svelte";
  import { toast } from "../state.svelte";

  // One group of team standards: its values as rows, named tables, lists, notes, and groups inside it.
  let {
    path,
    std,
    set,
  }: {
    path: string[];
    std: { team: Json; merged: Json; defaults: Json };
    set: (path: string[], value: Json) => void;
  } = $props();

  let cur = $derived((at(std.merged, path) ?? {}) as { [k: string]: Json });
  let def = $derived((at(std.defaults, path) ?? {}) as { [k: string]: Json });
  let keys = $derived([...new Set([...Object.keys(def), ...Object.keys(cur)])]);
  let notes = $derived(keys.filter((k) => isNote(k) && typeof (cur[k] ?? def[k]) === "string"));
  let rows = $derived(keys.filter((k) => !isNote(k) && !NAMED.has(k) && !Array.isArray(cur[k] ?? def[k]) && !isObject(cur[k] ?? def[k])));
  let tables = $derived(keys.filter((k) => NAMED.has(k)));
  let lists = $derived(keys.filter((k) => Array.isArray(cur[k] ?? def[k])));
  let groups = $derived(keys.filter((k) => !NAMED.has(k) && isObject(cur[k] ?? def[k])));

  const teamHas = (p: string[]) => at(std.team, p) !== undefined;
  const shown = (v: Json | undefined) => (v === null || v === undefined ? "" : String(v));
  const columnsOf = (items: Json[]) => [...new Set(items.flatMap((i) => (isObject(i) ? Object.keys(i) : [])))];
  let adding = $state<Record<string, string>>({});
  /** What a value means: by its path ("recovery.openingForceCoefficient"), or by its name for tables and lists. */
  const help = (k: string) => STD_HELP[[...path, k].join(".")] ?? STD_HELP[k];

  // Right-click on a value: back to the default, copy it; on a table row: remove it, copy it.
  function valueItems(k: string, p: string[], v: Json | undefined): MenuItem[] {
    const text = shown(v ?? def[k]);
    return [
      { header: label(k) },
      { label: `Back to the default (${shown(def[k]) || "not set"})`, off: !teamHas(p) && "Already the default", run: () => set(p, null) },
      { label: "Copy value", off: !text && "Empty", run: () => copyText(text).then((ok) => ok && toast(`Copied ${text}`)) },
      "-",
      ...(currentPageItems() as MenuItem[]),
    ];
  }
  function rowItems(name: string, p: string[], value: Json, remove: () => void, isDefault: boolean): MenuItem[] {
    return [
      { header: name },
      { label: isDefault ? "Back to the default" : "Remove", danger: !isDefault, off: !teamHas(p) && isDefault && "Already the default", run: remove },
      { label: "Copy row", run: () => copyText(`${name}: ${JSON.stringify(value)}`).then((ok) => ok && toast("Copied")) },
      "-",
      ...(currentPageItems() as MenuItem[]),
    ];
  }
</script>

{#if notes.length}
  <!-- The defaults' sources and assumptions: folded, so the values come first. -->
  <details class="notes">
    <summary>Sources and assumptions ({notes.length})</summary>
    {#each notes as k (k)}<p class="note">{cur[k] ?? def[k]}</p>{/each}
  </details>
{/if}

{#each rows as k (k)}
  {@const p = [...path, k]}
  {@const v = cur[k]}
  <div class="row" class:team={teamHas(p)} data-std={p.join(".")} use:context={() => valueItems(k, p, v)}>
    <span class="name" title={k}>{label(k)}{#if help(k)}<Tip label="About {label(k)}">{help(k)}</Tip>{/if}</span>
    {#if typeof (v ?? def[k]) === "boolean"}
      <input type="checkbox" checked={!!v} onchange={(e) => set(p, e.currentTarget.checked)} />
    {:else}
      <input class="input" value={shown(v)} placeholder={def[k] === null || def[k] === "" ? "not set" : ""} onchange={(e) => set(p, parse(e.currentTarget.value, def[k]))} />
    {/if}
    <span class="who">
      {#if teamHas(p)}
        <button class="tool" title="Back to the default ({shown(def[k]) || 'not set'})" onclick={() => set(p, null)}><RotateCcw size={13} /></button>
      {:else}<span class="muted">default</span>{/if}
    </span>
  </div>
{/each}

{#each tables as k (k)}
  {@const entries = Object.entries(isObject(cur[k]) ? cur[k] : {}).filter(([n]) => !isNote(n))}
  {@const cols = columnsOf(entries.map(([, e]) => e)).filter((c) => c !== "source")}
  <!-- Long tables (a property for every material) start folded, so a section fits on the screen. -->
  <details class="block" open={entries.length <= 4} data-std={[...path, k].join(".")}>
    <summary class="title">{label(k)} <span class="count">{entries.length}</span>{#if help(k)}<Tip label="About {label(k)}">{help(k)}</Tip>{/if}</summary>
    <table class="grid">
      <thead><tr><th>Name</th>{#if cols.length}{#each cols as c (c)}<th>{label(c)}</th>{/each}{:else}<th>Value</th>{/if}<th>Source</th><th></th></tr></thead>
      <tbody>
        {#each entries as [n, e] (n)}
          {@const p = [...path, k, n]}
          <tr class:team={teamHas(p)} use:context={() => rowItems(n, p, e, () => set(p, null), at(std.defaults, p) !== undefined)}>
            <td class="key">{n}</td>
            {#if isObject(e)}
              {#each cols as c (c)}
                <td><input class="input" value={shown(e[c])} onchange={(ev) => set([...p, c], parse(ev.currentTarget.value, e[c]))} /></td>
              {/each}
              <td><input class="input src" value={shown(e.source)} onchange={(ev) => set([...p, "source"], parse(ev.currentTarget.value, ""))} /></td>
            {:else}
              <td><input class="input" value={shown(e)} onchange={(ev) => set(p, parse(ev.currentTarget.value, e))} /></td><td></td>
            {/if}
            <td class="acts">
              {#if teamHas(p)}
                <button class="tool" title={at(std.defaults, p) === undefined ? "Remove" : "Back to the default"} onclick={() => set(p, null)}>
                  {#if at(std.defaults, p) === undefined}<Trash2 size={13} />{:else}<RotateCcw size={13} />{/if}
                </button>
              {/if}
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
    <div class="add">
      <input class="input" placeholder={k === "shearPins" ? "New pin, e.g. 2-56 nylon" : k === "measuredPackingFactors" ? "New bay, e.g. 2027 main bay" : "New material, e.g. kevlar"} bind:value={adding[k]} />
      <button class="btn" disabled={!adding[k]?.trim()} onclick={() => {
        const first = cols[0];
        set([...path, k, adding[k].trim()], first ? { [first]: "" } : "");
        adding[k] = "";
      }}><Plus size={13} /> Add</button>
    </div>
  </details>
{/each}

{#each lists as k (k)}
  {@const items = (Array.isArray(cur[k]) ? cur[k] : []) as Json[]}
  {@const cols = columnsOf(items)}
  {@const p = [...path, k]}
  <div class="block" data-std={p.join(".")}>
    <div class="title">
      {label(k)}{#if help(k)}<Tip label="About {label(k)}">{help(k)}</Tip>{/if}
      {#if teamHas(p)}<button class="tool" title="Back to the defaults" onclick={() => set(p, null)}><RotateCcw size={13} /></button>{:else}<span class="muted small">default</span>{/if}
    </div>
    <div class="scroll">
      <table class="grid">
        <thead><tr>{#each cols as c (c)}<th>{label(c)}</th>{/each}<th></th></tr></thead>
        <tbody>
          {#each items as item, i (i)}
            <tr use:context={() => [
              { header: `${label(k)}, row ${i + 1}` },
              { label: "Duplicate row", run: () => set(p, [...items.slice(0, i + 1), item, ...items.slice(i + 1)]) },
              { label: "Move up", off: i === 0 && "Already first", run: () => set(p, items.map((x, j) => (j === i - 1 ? items[i] : j === i ? items[i - 1] : x))) },
              { label: "Move down", off: i === items.length - 1 && "Already last", run: () => set(p, items.map((x, j) => (j === i + 1 ? items[i] : j === i ? items[i + 1] : x))) },
              { label: "Remove row", danger: true, run: () => set(p, items.filter((_, j) => j !== i)) },
              "-",
              ...(currentPageItems() as MenuItem[]),
            ]}>
              {#each cols as c (c)}
                {@const v = isObject(item) ? item[c] : null}
                <td><input class="input" value={shown(v)} onchange={(ev) => {
                  const next = items.map((x, j) => (j === i && isObject(x) ? { ...x, [c]: parse(ev.currentTarget.value, v) } : x));
                  set(p, next);
                }} /></td>
              {/each}
              <td class="acts"><button class="tool" title="Remove" onclick={() => set(p, items.filter((_, j) => j !== i))}><Trash2 size={13} /></button></td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
    <button class="btn add1" onclick={() => set(p, [...items, Object.fromEntries(cols.map((c) => [c, null]))])}><Plus size={13} /> Row</button>
  </div>
{/each}

{#each groups as k (k)}
  <div class="group">
    <div class="title">{label(k)}</div>
    <StdNode path={[...path, k]} {std} {set} />
  </div>
{/each}

<style>
  .notes {
    margin: 0 0 6px;
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
  }
  .notes summary {
    cursor: pointer;
    width: max-content;
  }
  .notes summary:hover {
    color: var(--select);
  }
  .note {
    margin: 2px 0 8px;
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
  }
  :global([data-std].found) {
    animation: found 1.6s ease-out;
    border-radius: 6px;
  }
  @keyframes -global-found {
    0%,
    40% {
      background: var(--select-soft);
      box-shadow: 0 0 0 2px var(--select);
    }
  }
  .row {
    display: grid;
    grid-template-columns: minmax(160px, 40%) 1fr 70px;
    align-items: center;
    gap: 12px;
    min-height: 40px;
  }
  .row .name {
    color: var(--ink-2);
  }
  .row.team .name::after,
  tr.team .key::after {
    content: "●";
    color: var(--select);
    font-size: calc(8px * var(--text));
    margin-left: 5px;
    vertical-align: middle;
  }
  .row input[type="checkbox"] {
    justify-self: start;
    width: 16px;
    height: 16px;
  }
  .who {
    font-size: calc(11.5px * var(--text));
  }
  .block,
  .group {
    margin: 10px 0 4px;
  }
  .group {
    padding-left: 12px;
    border-left: 2px solid var(--line);
  }
  summary.title {
    cursor: pointer;
    list-style: none;
  }
  summary.title::-webkit-details-marker {
    display: none;
  }
  summary.title::before {
    content: "▸";
    color: var(--ink-3);
    width: 10px;
  }
  details[open] > summary.title::before {
    content: "▾";
  }
  summary.title:hover {
    color: var(--select);
  }
  .count {
    font-weight: 400;
    font-size: calc(11px * var(--text));
    color: var(--ink-3);
  }
  .title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-weight: 600;
    font-size: calc(12.5px * var(--text));
    margin-bottom: 4px;
  }
  .small {
    font-size: calc(11.5px * var(--text));
    font-weight: 400;
  }
  .scroll {
    overflow: auto;
  }
  th {
    text-align: left;
    font-size: calc(11.5px * var(--text));
    font-weight: 600;
    color: var(--ink-3);
    white-space: nowrap;
  }
  td {
    padding: 1px 3px;
  }
  td .input {
    width: 100%;
    min-width: 70px;
  }
  .src {
    min-width: 160px;
  }
  .key {
    white-space: nowrap;
    font-size: calc(12.5px * var(--text));
  }
  .acts {
    width: 1%;
  }
  .tool {
    display: inline-grid;
    place-items: center;
    width: 22px;
    height: 22px;
    border: 0;
    background: transparent;
    color: var(--ink-3);
    border-radius: 3px;
  }
  .tool:hover {
    background: var(--surface-3);
    color: var(--ink);
  }
  .add {
    display: flex;
    gap: 6px;
    margin-top: 4px;
  }
  .add .input {
    max-width: 220px;
  }
  .add1 {
    margin-top: 4px;
    height: 24px;
  }
</style>
