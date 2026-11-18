<script lang="ts">
  import { ChevronDown } from "@lucide/svelte";
  import { act } from "../state.svelte";
  import { isOpen, toggle } from "../panels.svelte";
  import QuantityInput from "../design/QuantityInput.svelte";
  import SimExtensions from "./SimExtensions.svelte";
  import { editNum, readNum } from "../units.svelte";

  // OpenRocket's simulation dialog ("Launch conditions" and "Simulation options") as one form.
  let { sim, configs }: { sim: { index: number; name: string; configuration: string; options: Record<string, any> }; configs: { id: string; name: string }[] } =
    $props();

  function set(key: string, value: unknown) {
    act("app_sim_edit", { simulation: sim.index, key, value });
  }

  let name = $state("");
  $effect(() => {
    name = sim.name;
  });
  let o = $derived(sim.options);
</script>

{#snippet head(id: string, title: string, initial: boolean)}
  <button class="st" onclick={() => toggle("sim." + id, initial)} aria-expanded={isOpen("sim." + id, initial)}>
    <span class="chev" class:shut={!isOpen("sim." + id, initial)}><ChevronDown size={14} /></span>
    {title}
  </button>
{/snippet}

<!-- Every section starts closed: open the ones you need (each is remembered). -->
<div class="f">
  <section>
    {@render head("general", "Simulation", false)}
    {#if isOpen("sim.general", false)}
      <div class="rows">
        <label class="row"><span>Name</span>
          <input class="input" bind:value={name} onblur={() => name !== sim.name && set("name", name)} onkeydown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()} />
        </label>
        <label class="row"><span>Flight configuration</span>
          <select class="select" value={sim.configuration} onchange={(e) => set("configuration", (e.target as HTMLSelectElement).value)}>
            {#each configs as c (c.id)}<option value={c.id}>{c.name}</option>{/each}
          </select>
        </label>
      </div>
    {/if}
  </section>

  <section>
    {@render head("wind", "Wind", false)}
    {#if isOpen("sim.wind", false)}
      <div class="rows">
        {#if o.windProfile}<p class="note">Uses a wind profile by altitude; the speed here scales it from the ground.</p>{/if}
        <div class="row"><span>Average speed</span><QuantityInput value={o.windSpeed} dim="VELOCITY" commit={(v) => set("windSpeed", v)} /></div>
        <div class="row"><span>Direction (from)</span><QuantityInput value={o.windDirection} dim="ANGLE" commit={(v) => set("windDirection", v)} /></div>
        <div class="row"><span>Turbulence intensity</span>
          <div class="pct">
            <input class="input num" value={editNum(Math.round((o.windTurbulence ?? 0) * 1000) / 10, 6)}
              onchange={(e) => set("windTurbulence", Number(readNum((e.target as HTMLInputElement).value)) / 100)} />
            <span class="muted">%</span>
          </div>
        </div>
      </div>
    {/if}
  </section>

  <section>
    {@render head("site", "Launch site", false)}
    {#if isOpen("sim.site", false)}
      <div class="rows">
        <div class="row"><span>Altitude (sea level)</span><QuantityInput value={o.launchAltitude} dim="DISTANCE" commit={(v) => set("launchAltitude", v)} /></div>
        <div class="row"><span>Latitude (°N)</span>
          <input class="input num" value={editNum(o.latitude, 8)} onchange={(e) => set("latitude", Number(readNum((e.target as HTMLInputElement).value)))} />
        </div>
        <div class="row"><span>Longitude (°E)</span>
          <input class="input num" value={editNum(o.longitude, 8)} onchange={(e) => set("longitude", Number(readNum((e.target as HTMLInputElement).value)))} />
        </div>
        <label class="row check"><input type="checkbox" checked={o.isaAtmosphere} onchange={(e) => set("isaAtmosphere", (e.target as HTMLInputElement).checked)} /> International Standard Atmosphere</label>
        {#if !o.isaAtmosphere}
          <div class="row"><span>Temperature</span><QuantityInput value={o.temperature} dim="TEMPERATURE" commit={(v) => set("temperature", v)} /></div>
          <div class="row"><span>Pressure</span><QuantityInput value={o.pressure} dim="PRESSURE" commit={(v) => set("pressure", v)} /></div>
        {/if}
      </div>
    {/if}
  </section>

  <section>
    {@render head("rail", "Launch rail", false)}
    {#if isOpen("sim.rail", false)}
      <div class="rows">
        <div class="row"><span>Length</span><QuantityInput value={o.rodLength} dim="LENGTH" commit={(v) => set("rodLength", v)} /></div>
        <div class="row"><span>Angle from vertical</span><QuantityInput value={o.rodAngle} dim="ANGLE" commit={(v) => set("rodAngle", v)} /></div>
        <label class="row check"><input type="checkbox" checked={o.launchIntoWind} onchange={(e) => set("launchIntoWind", (e.target as HTMLInputElement).checked)} /> Into the wind</label>
        {#if !o.launchIntoWind}
          <div class="row"><span>Direction</span><QuantityInput value={o.rodDirection} dim="ANGLE" commit={(v) => set("rodDirection", v)} /></div>
        {/if}
      </div>
    {/if}
  </section>

  <section>
    {@render head("options", "Simulation options", false)}
    {#if isOpen("sim.options", false)}
      <div class="rows">
        <div class="row"><span>Time step</span><QuantityInput value={o.timeStep} dim="TIME" commit={(v) => set("timeStep", v)} /></div>
      </div>
    {/if}
  </section>

  <section>
    {@render head("extensions", "Extensions", false)}
    {#if isOpen("sim.extensions", false)}
      <SimExtensions sim={sim.index} />
    {/if}
  </section>
</div>

<style>
  section {
    border-bottom: 1px solid var(--line);
  }
  section:last-child {
    border-bottom: 0;
  }
  .st {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 4px;
    border: 0;
    background: transparent;
    padding: 6px 8px;
    font-weight: 600;
    text-align: left;
  }
  .st:hover {
    background: var(--surface-2);
  }
  .chev {
    display: flex;
    color: var(--ink-3);
    transition: transform 0.1s;
  }
  .chev.shut {
    transform: rotate(-90deg);
  }
  .rows {
    display: flex;
    flex-direction: column;
    gap: 3px;
    padding: 0 10px 8px 26px;
  }
  .row {
    display: grid;
    grid-template-columns: 46% 1fr;
    align-items: center;
    gap: 6px;
    min-height: 28px;
    color: var(--ink-2);
  }
  .row.check {
    display: flex;
    gap: 6px;
  }
  .row .input,
  .row .select {
    width: 100%;
  }
  .input.num {
    text-align: right;
  }
  .pct {
    display: flex;
    gap: 6px;
    align-items: center;
  }
  .note {
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
    margin: 0;
  }
</style>
