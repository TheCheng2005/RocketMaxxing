<script lang="ts">
  import { onMount } from "svelte";
  import { Plus, Trash2 } from "@lucide/svelte";
  import { call } from "../api";
  import { app, run, toast } from "../state.svelte";
  import { units } from "../units.svelte";

  // The team's own materials (OpenRocket's Preferences > Materials): a laminate, a filament, a canopy cloth or a cord
  // with its density. Kept beside the designs; every part's material list offers them.
  interface Mat {
    type: "bulk" | "surface" | "line";
    name: string;
    density: number;
  }
  let list = $state<Mat[]>([]);
  let type = $state<Mat["type"]>("bulk");
  let name = $state("");
  let density = $state("");
  const KIND: Record<Mat["type"], string> = { bulk: "Solid", surface: "Cloth", line: "Line or cord" };
  const imperial = $derived(units.system === "imperial");
  const EXAMPLE: Record<Mat["type"], [string, string]> = {
    bulk: ["1.85 g/cm3", "0.067 lb/in3"],
    surface: ["40 g/m2", "1.2 oz/yd2"],
    line: ["2 g/m", "0.02 oz/ft"],
  };

  /** Density as the team reads it: g/cm³, g/m² or g/m (oz in imperial). */
  function shown(m: Mat): string {
    if (m.type === "bulk") return imperial ? `${(m.density / 27679.9).toPrecision(3)} lb/in³` : `${(m.density / 1000).toPrecision(3)} g/cm³`;
    if (m.type === "surface") return imperial ? `${(m.density / 0.0339057).toPrecision(3)} oz/yd²` : `${(m.density * 1000).toPrecision(3)} g/m²`;
    return imperial ? `${(m.density / 0.0930102).toPrecision(3)} oz/ft` : `${(m.density * 1000).toPrecision(3)} g/m`;
  }

  async function load() {
    const r = await call<{ materials: Mat[] }>("materials", {}).catch(() => null);
    if (r) list = r.materials;
  }
  /** The parts' material lists come with the session: read it again so they offer the change. */
  async function refreshSession() {
    app.session = await call<any>("app_session").catch(() => app.session);
  }
  async function add(e: SubmitEvent) {
    e.preventDefault();
    if (!name.trim() || !density.trim()) return;
    const r = await run(() => call<{ materials: Mat[]; added: string }>("materials", { action: "add", type, name: name.trim(), density: density.trim() }), "Materials");
    if (r) {
      list = r.materials;
      toast(`${r.added} is in every part's material list`);
      name = density = "";
      refreshSession();
    }
  }
  async function remove(m: Mat) {
    const r = await run(() => call<{ materials: Mat[] }>("materials", { action: "remove", name: m.name }), "Materials");
    if (r) {
      list = r.materials;
      toast(`Removed ${m.name}; parts already made of it keep it`);
      refreshSession();
    }
  }
  onMount(load);
</script>

<div class="mats">
  {#if list.length}
    <table class="grid">
      <thead><tr><th>Material</th><th>Kind</th><th class="n">Density</th><th></th></tr></thead>
      <tbody>
        {#each list as m (m.type + m.name)}
          <tr>
            <td>{m.name}</td>
            <td class="muted">{KIND[m.type]}</td>
            <td class="n">{shown(m)}</td>
            <td class="n"><button class="tool" title="Remove {m.name}" onclick={() => remove(m)}><Trash2 size={13} /></button></td>
          </tr>
        {/each}
      </tbody>
    </table>
  {:else}
    <p class="muted">No materials of your own yet. Add a laminate, a printing filament, a canopy cloth or a cord, and every part's material list offers it.</p>
  {/if}
  <form class="add" onsubmit={add}>
    <select class="select" bind:value={type} aria-label="Kind of material">
      <option value="bulk">Solid</option><option value="surface">Cloth</option><option value="line">Line or cord</option>
    </select>
    <input class="input name" bind:value={name} placeholder="Name, e.g. Team G10 layup" aria-label="Material name" />
    <input class="input dens" bind:value={density} placeholder={EXAMPLE[type][imperial ? 1 : 0]} aria-label="Density with its unit" />
    <button class="btn" disabled={!name.trim() || !density.trim()}><Plus size={14} /> Add</button>
  </form>
</div>

<style>
  .mats {
    padding: 10px 14px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .mats p {
    margin: 0;
  }
  .grid {
    width: 100%;
  }
  .n {
    text-align: right;
    white-space: nowrap;
  }
  .add {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
  }
  .name {
    flex: 1;
    min-width: 10em;
  }
  .dens {
    width: 9em;
  }
</style>
