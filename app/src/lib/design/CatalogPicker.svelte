<script lang="ts">
  import { untrack } from "svelte";
  import { X } from "@lucide/svelte";
  import { call } from "../api";
  import { app, act, quietly } from "../state.svelte";
  import { fmt } from "../units.svelte";

  // OpenRocket's parts database for the selected part: the parts nearest its diameter first. Picking one loads its
  // dimensions, material and mass (undo puts the old ones back).
  let { component, close }: { component: string; close: () => void } = $props();

  interface Preset {
    ref: string;
    manufacturer: string;
    partNo: string;
    description: string;
    outerDiameter: number | null;
    innerDiameter: number | null;
    length: number | null;
    mass: number | null;
    material: string;
  }

  let text = $state("");
  let same = $state(true);
  let result = $state<{ type: string; diameter: number | null; current: string | null; matches: number; presets: Preset[] } | null>(null);
  let error = $state("");
  let loading = $state(false);
  let timer: ReturnType<typeof setTimeout> | undefined;

  $effect(() => {
    const args = { text, same };
    clearTimeout(timer);
    timer = setTimeout(() => untrack(() => search(args.text, args.same)), 200);
  });

  async function search(t: string, sameDiameter: boolean) {
    loading = true;
    try {
      result = await call("app_presets", { designId: app.design!.designId, component, text: t, sameDiameter });
      error = "";
    } catch (e) {
      error = quietly(e, "Parts catalogue");
    } finally {
      loading = false;
    }
  }

  // Most catalogues give no mass (OpenRocket works it out from the material): the column shows only when one does.
  let masses = $derived(result?.presets.some((p) => p.mass) ?? false);

  async function pick(p: Preset) {
    await act("apply_preset", { component, preset: p.ref });
    close();
  }
</script>

<svelte:window onkeydown={(e) => e.key === "Escape" && close()} />

<div class="scrim" role="presentation" onclick={(e) => e.target === e.currentTarget && close()}>
  <div class="dlg" role="dialog" aria-label="Parts catalogue">
    <header>
      <h3>Parts catalogue{#if result}: {result.type.replace(/_/g, " ").toLowerCase()}{/if}</h3>
      <button class="btn ghost" onclick={close} aria-label="Close"><X size={16} /></button>
    </header>
    <div class="bar">
      <input class="input" bind:value={text} placeholder="Manufacturer, part number or material (e.g. LOC, fiberglass)" />
      <label><input type="checkbox" bind:checked={same} /> Same diameter{result?.diameter ? ` (${fmt(result.diameter, "LENGTH")})` : ""}</label>
    </div>
    <div class="list" class:stale={loading}>
      {#if error}
        <p class="muted pad">{error}</p>
      {:else if result && !result.presets.length}
        <p class="muted pad">No parts match{same ? "; try without the same-diameter filter" : ""}.</p>
      {:else if result}
        <table class="grid">
          <thead>
            <tr><th>Part</th><th>Description</th><th class="n">Outer ⌀</th><th class="n">Inner ⌀</th><th class="n">Length</th>{#if masses}<th class="n">Mass</th>{/if}<th>Material</th></tr>
          </thead>
          <tbody>
            {#each result.presets as p (p.ref)}
              <tr class:on={p.ref === result.current} onclick={() => pick(p)} title="Use this part">
                <td class="part"><b>{p.manufacturer}</b> {p.partNo}</td>
                <td class="desc">{p.description}</td>
                <td class="n">{fmt(p.outerDiameter, "LENGTH")}</td>
                <td class="n">{fmt(p.innerDiameter, "LENGTH")}</td>
                <td class="n">{fmt(p.length, "LENGTH")}</td>
                {#if masses}<td class="n">{fmt(p.mass, "MASS")}</td>{/if}
                <td>{p.material}</td>
              </tr>
            {/each}
          </tbody>
        </table>
      {/if}
    </div>
    <footer class="muted">
      {#if result}{result.matches} part{result.matches === 1 ? "" : "s"}{result.matches > result.presets.length ? `, first ${result.presets.length} shown` : ""}.{/if}
      Click a part to use it.
    </footer>
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
    width: min(920px, calc(100vw - 32px));
    height: min(640px, calc(100vh - 40px));
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
    font-size: 15px;
  }
  .bar {
    display: flex;
    gap: 12px;
    align-items: center;
    padding: 8px 16px;
    border-bottom: 1px solid var(--line);
  }
  .bar .input {
    flex: 1;
  }
  .bar label {
    display: flex;
    gap: 5px;
    align-items: center;
    white-space: nowrap;
    font-size: 12.5px;
    color: var(--ink-2);
  }
  .list {
    flex: 1;
    overflow: auto;
  }
  .list.stale {
    opacity: 0.6;
  }
  th {
    position: sticky;
    top: 0;
    background: var(--surface-2);
    text-align: left;
    font-size: 12px;
    font-weight: 600;
    color: var(--ink-3);
  }
  tbody tr {
    cursor: pointer;
  }
  tbody tr:hover td {
    background: var(--surface-2);
  }
  tr.on td {
    background: var(--select-soft, var(--surface-3));
  }
  td {
    font-size: 12.5px;
  }
  .part {
    white-space: nowrap;
  }
  .desc {
    color: var(--ink-2);
  }
  .n {
    text-align: right;
    white-space: nowrap;
  }
  .pad {
    padding: 12px 16px;
    margin: 0;
  }
  footer {
    padding: 6px 16px;
    border-top: 1px solid var(--line);
    font-size: 12px;
  }
</style>
