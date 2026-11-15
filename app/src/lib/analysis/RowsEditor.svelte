<script lang="ts">
  import { Plus, X } from "@lucide/svelte";
  import type { TreeNode } from "../state.svelte";
  import { label } from "./text";

  // A list of items (bay contents, sections, circuits, optimizer variables...) as an editable table.
  // The rows live in `value` as JSON text, like the page's other inputs.
  let {
    items,
    value = $bindable(),
    parts = [],
  }: { items: any; value?: string; parts?: TreeNode[] } = $props();

  let cols = $derived(Object.entries(items?.properties ?? {}) as [string, any][]);
  let required = $derived(new Set<string>(items?.required ?? []));

  function read(): Record<string, string>[] {
    try {
      const r = JSON.parse(value || "[]");
      return Array.isArray(r) && r.length ? r : [{}];
    } catch {
      return [{}];
    }
  }

  let rows = $derived(read());

  function set(i: number, key: string, v: string) {
    const r = read();
    r[i] = { ...r[i], [key]: v };
    value = JSON.stringify(r);
  }

  function add() {
    value = JSON.stringify([...read(), {}]);
  }

  function remove(i: number) {
    const r = read();
    r.splice(i, 1);
    value = JSON.stringify(r);
  }
</script>

<div class="rows">
  <table>
    <thead>
      <tr>
        {#each cols as [k, s] (k)}<th title={s.description ?? ""}>{label(k)}{#if required.has(k)}<b>*</b>{/if}</th>{/each}
        <th></th>
      </tr>
    </thead>
    <tbody>
      {#each rows as row, i (i)}
        <tr>
          {#each cols as [k, s] (k)}
            <td>
              {#if k === "component" && parts.length}
                <select class="select" value={row[k] ?? ""} onchange={(e) => set(i, k, (e.target as HTMLSelectElement).value)}>
                  <option value=""></option>
                  {#each parts as p (p.id)}<option value={p.id}>{p.name}</option>{/each}
                </select>
              {:else if s.type === "boolean"}
                <select class="select" value={row[k] ?? ""} onchange={(e) => set(i, k, (e.target as HTMLSelectElement).value)}>
                  <option value=""></option><option value="true">yes</option><option value="false">no</option>
                </select>
              {:else if s.enum}
                <select class="select" value={row[k] ?? ""} onchange={(e) => set(i, k, (e.target as HTMLSelectElement).value)}>
                  <option value=""></option>
                  {#each s.enum as e}<option value={e}>{e}</option>{/each}
                </select>
              {:else}
                <input class="input" value={row[k] ?? ""} placeholder={Array.isArray(s.type) ? "with unit" : ""}
                  onchange={(e) => set(i, k, (e.target as HTMLInputElement).value)} />
              {/if}
            </td>
          {/each}
          <td><button class="x" title="Remove row" onclick={() => remove(i)}><X size={13} /></button></td>
        </tr>
      {/each}
    </tbody>
  </table>
  <button class="btn add" onclick={add}><Plus size={13} /> Row</button>
</div>

<style>
  .rows {
    grid-column: 1 / -1;
    overflow: auto;
  }
  table {
    border-collapse: collapse;
  }
  th {
    font-weight: 600;
    font-size: calc(12px * var(--text));
    color: var(--ink-2);
    text-align: left;
    padding: 2px 4px;
    white-space: nowrap;
  }
  th b {
    color: var(--bad);
    margin-left: 2px;
  }
  td {
    padding: 2px 3px;
  }
  .input,
  .select {
    width: 120px;
  }
  .x {
    display: grid;
    place-items: center;
    width: 22px;
    height: 22px;
    border: 0;
    background: transparent;
    color: var(--ink-3);
  }
  .x:hover {
    color: var(--bad);
  }
  .add {
    height: 24px;
    margin: 4px 3px;
  }
</style>
