<script lang="ts">
  import { FolderOpen } from "@lucide/svelte";
  import { call } from "../api";
  import { report } from "../state.svelte";
  import { isPath, isPrimitive, label, status } from "./text";
  import Self from "./Result.svelte";

  // Any engine result as tables: objects as rows, lists of rows as tables, nested parts in folding sections.
  let { value, depth = 0 }: { value: any; depth?: number } = $props();

  /** A list of flat objects with few enough columns reads best as a table. */
  function columns(list: any[]): string[] | null {
    if (!list.length || !list.every((x) => x && typeof x === "object" && !Array.isArray(x))) return null;
    const cols: string[] = [];
    for (const row of list) {
      for (const [k, v] of Object.entries(row)) {
        if (!isPrimitive(v) && !(Array.isArray(v) && v.length <= 4 && v.every(isPrimitive))) return null;
        if (!cols.includes(k)) cols.push(k);
      }
    }
    return cols.length <= 9 ? cols : null;
  }

  /** A heading for one item of a list: its name-like field. */
  function heading(item: any, i: number): string {
    for (const k of ["item", "name", "check", "joint", "device", "finSet", "part", "area", "motor", "section", "label"]) {
      if (typeof item?.[k] === "string") return item[k];
    }
    return `#${i + 1}`;
  }

  async function reveal(path: string) {
    try {
      await call("app_reveal", { path });
    } catch (e) {
      report(e);
    }
  }
</script>

{#snippet val(k: string, v: any)}
  {#if v === null || v === undefined}
    <span class="muted">–</span>
  {:else if Array.isArray(v)}
    {v.join(", ")}
  {:else if typeof v === "boolean"}
    {v ? "yes" : "no"}
  {:else if isPath(k, v)}
    <span class="path">{v}</span>
    <button class="show" onclick={() => reveal(v)} title="Show in the file manager"><FolderOpen size={13} /> Show</button>
  {:else if status(v)}
    <span class="st {status(v)}">{v}</span>
  {:else}
    {v}
  {/if}
{/snippet}

{#if isPrimitive(value)}
  <div class="plain">{@render val("", value)}</div>
{:else if Array.isArray(value)}
  {@const cols = columns(value)}
  {#if value.length === 0}
    <span class="muted">none</span>
  {:else if value.every(isPrimitive)}
    <ul class="list">
      {#each value as v}<li>{@render val("", v)}</li>{/each}
    </ul>
  {:else if cols}
    <div class="tablewrap">
      <table class="grid">
        <thead><tr>{#each cols as c}<th>{label(c)}</th>{/each}</tr></thead>
        <tbody>
          {#each value as row}
            <tr class={status(row.status)}>{#each cols as c}<td>{@render val(c, row[c])}</td>{/each}</tr>
          {/each}
        </tbody>
      </table>
    </div>
  {:else}
    {#each value as item, i}
      <details class="sub" open={depth <= 1 && value.length <= 4}>
        <summary>{heading(item, i)}{#if status(item?.status)} <span class="st {status(item.status)}">{item.status}</span>{/if}</summary>
        <div class="inner"><Self value={item} depth={depth + 1} /></div>
      </details>
    {/each}
  {/if}
{:else}
  {@const entries = Object.entries(value ?? {})}
  {@const flat = entries.filter(([, v]) => isPrimitive(v))}
  {@const nested = entries.filter(([, v]) => !isPrimitive(v))}
  {#if flat.length}
    <dl class="rows">
      {#each flat as [k, v] (k)}
        <dt>{label(k)}</dt>
        <dd>{@render val(k, v)}</dd>
      {/each}
    </dl>
  {/if}
  {#each nested as [k, v] (k)}
    <details class="sub" open={depth < 1}>
      <summary>{label(k)}{#if Array.isArray(v)} <span class="muted">({v.length})</span>{/if}</summary>
      <div class="inner"><Self value={v} depth={depth + 1} /></div>
    </details>
  {/each}
{/if}

<style>
  .rows {
    display: grid;
    grid-template-columns: minmax(140px, 32%) 1fr;
    gap: 3px 12px;
    margin: 0;
    padding: 4px 0;
  }
  dt {
    color: var(--ink-2);
  }
  dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
  .sub {
    border-top: 1px solid var(--line);
  }
  summary {
    cursor: pointer;
    padding: 5px 0;
    font-weight: 600;
  }
  .inner {
    padding: 0 0 6px 14px;
  }
  .list {
    margin: 2px 0;
    padding-left: 18px;
  }
  .tablewrap {
    overflow: auto;
    max-width: 100%;
    margin: 2px 0 6px;
  }
  .tablewrap table {
    border: 1px solid var(--line);
  }
  .st {
    font-weight: 600;
  }
  .st.pass {
    color: var(--good);
  }
  .st.warn {
    color: var(--warn);
  }
  .st.fail {
    color: var(--bad);
  }
  tr.fail td {
    background: var(--bad-soft);
  }
  tr.warn td {
    background: var(--warn-soft);
  }
  .path {
    font-family: var(--mono);
    font-size: calc(12px * var(--text));
  }
  .show {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    margin-left: 6px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface);
    padding: 0 6px;
    font-size: calc(12px * var(--text));
  }
</style>
