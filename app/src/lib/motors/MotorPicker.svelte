<script lang="ts">
  import { X, Search, FileUp, FlaskConical } from "@lucide/svelte";
  import CustomMotorDialog from "./CustomMotorDialog.svelte";
  import { call } from "../api";
  import { act, app, run } from "../state.svelte";
  import { fmt, num, pretty, toShown, unitOf } from "../units.svelte";
  import Plot from "../Plot.svelte";

  // OpenRocket's motor selection dialog: search the database, see the thrust curve, pick the delay.
  let {
    mount,
    configuration,
    close,
    start,
  }: { mount: { id: string; name: string; innerDiameter: number }; configuration: string; close: () => void; start?: "import" | "custom" } = $props();

  interface MotorRow {
    digest: string;
    designation: string;
    commonName: string;
    manufacturer: string;
    class: string;
    diameter: number;
    length: number;
    totalImpulse: number;
    averageThrust: number;
    maxThrust: number;
    burnTime: number;
    launchMass: number;
    propellantMass: number;
    type: string;
    delays: number[];
    available: boolean;
  }

  const CLASSES = "ABCDEFGHIJKLMNOPQ".split("");
  let text = $state("");
  let minClass = $state("");
  let maxClass = $state("");
  let fits = $state(true);
  let availableOnly = $state(false);
  let rows = $state<MotorRow[]>([]);
  let matches = $state(0);
  let chosen = $state<MotorRow | null>(null);
  let curve = $state<{ time: number[]; thrust: number[] } | null>(null);
  let delay = $state("auto");
  let reload = $state(0);
  let custom = $state(false);

  let timer: ReturnType<typeof setTimeout>;
  $effect(() => {
    reload;
    const args: Record<string, unknown> = { availableOnly, limit: 400 };
    if (text.trim()) args.text = text.trim();
    if (minClass) args.minClass = minClass;
    if (maxClass) args.maxClass = maxClass;
    if (fits && app.design) Object.assign(args, { designId: app.design.designId, mount: mount.id });
    clearTimeout(timer);
    timer = setTimeout(async () => {
      const r = await run(() => call<{ matches: number; motors: MotorRow[] }>("app_motors", args));
      if (r) {
        rows = r.motors;
        matches = r.matches;
      }
    }, 200);
  });

  /** Shows a motor just added (it may not fit the mount yet: the filter is lifted) and selects it. */
  async function show(designation: string) {
    text = designation;
    fits = false;
    reload++;
    const r = await run(() => call<{ matches: number; motors: MotorRow[] }>("app_motors", { text: designation, limit: 50 }));
    const m = r?.motors.find((x) => x.designation === designation) ?? r?.motors[0];
    if (m) pick(m);
  }

  // Opened from the search to import a file or make a motor: go straight there.
  import { onMount } from "svelte";
  onMount(() => {
    if (start === "custom") custom = true;
    else if (start === "import") importFile();
  });

  async function importFile() {
    let path: string | null = null;
    try {
      path = (await call<{ path: string | null }>("app_choose_file", { mode: "open", types: "eng,rse,zip" })).path;
    } catch {
      /* no file dialog here */
    }
    if (!path) return;
    const r = await run(() => call<{ file: string; motors: MotorRow[] }>("app_import_motor", { path }));
    if (r?.motors.length) show(r.motors[0].designation);
  }

  async function pick(m: MotorRow) {
    chosen = m;
    delay = "auto";
    curve = (await run(() => call<{ time: number[]; thrust: number[] }>("app_motor_curve", { digest: m.digest }))) ?? null;
  }

  async function use() {
    if (!chosen) return;
    const args: Record<string, unknown> = { mount: mount.id, motor: chosen.digest, configuration };
    if (delay !== "auto" && delay !== "plugged") args.ejectionDelay = Number(delay);
    const r = await act("set_motor", args);
    if (r && delay === "plugged") await act("app_motor_delay", { mount: mount.id, configuration, plugged: true });
    if (r) close();
  }

  let delays = $derived(chosen ? chosen.delays.filter((d) => d !== null && Number.isFinite(d)) : []);
</script>

<div class="scrim" role="presentation" onclick={(e) => e.target === e.currentTarget && close()}>
  <div class="dlg" role="dialog" aria-label="Choose a motor">
    <header>
      <div>
        <h3>Motor for {mount.name}</h3>
        <div class="muted">Mount Ø {fmt(mount.innerDiameter, "LENGTH")}</div>
      </div>
      <div class="own">
        <button class="btn" onclick={importFile} title="A .eng, .rse or zipped motor file, e.g. from thrustcurve.org"><FileUp size={14} /> Import file…</button>
        <button class="btn" onclick={() => (custom = true)} title="From your static fire or engine data"><FlaskConical size={14} /> Your own motor…</button>
      </div>
      <button class="btn ghost" onclick={close} aria-label="Close"><X size={16} /></button>
    </header>
    <div class="filters">
      <label class="search">
        <Search size={16} />
        <input class="input" placeholder="Manufacturer or designation, e.g. CTI L1115" bind:value={text} />
      </label>
      <label>Class
        <select class="select" bind:value={minClass}><option value="">any</option>{#each CLASSES as c}<option>{c}</option>{/each}</select>
        to
        <select class="select" bind:value={maxClass}><option value="">any</option>{#each CLASSES as c}<option>{c}</option>{/each}</select>
      </label>
      <label class="check"><input type="checkbox" bind:checked={fits} /> Fits this mount</label>
      <label class="check"><input type="checkbox" bind:checked={availableOnly} /> Hide discontinued</label>
      <span class="muted count">{matches} found</span>
    </div>
    <div class="main">
      <div class="list">
        <table class="grid">
          <thead>
            <tr><th>Manufacturer</th><th>Motor</th><th>Class</th><th>Ø</th><th>Length</th><th>Impulse</th><th>Avg thrust</th><th>Burn</th></tr>
          </thead>
          <tbody>
            {#each rows as m (m.digest)}
              <tr class:sel={chosen?.digest === m.digest} onclick={() => pick(m)} ondblclick={() => (pick(m), use())}>
                <td>{m.manufacturer}</td>
                <td><b>{m.designation}</b>{#if !m.available} <span class="muted">(discontinued)</span>{/if}</td>
                <td>{m.class}</td>
                <td class="num">{fmt(m.diameter, "LENGTH", 3)}</td>
                <td class="num">{fmt(m.length, "LENGTH", 3)}</td>
                <td class="num">{fmt(m.totalImpulse, "IMPULSE")}</td>
                <td class="num">{fmt(m.averageThrust, "FORCE")}</td>
                <td class="num">{fmt(m.burnTime, "TIME", 3)}</td>
              </tr>
            {/each}
          </tbody>
        </table>
        {#if rows.length === 0}<p class="muted empty">No matching motors.</p>{/if}
      </div>
      <aside class="detail">
        {#if chosen}
          <h4>{chosen.manufacturer} {chosen.designation}</h4>
          {#if curve}
            <Plot x={curve?.time ?? []} series={[{ label: "Thrust", values: (curve?.thrust ?? []).map((t) => toShown(t, "FORCE")), color: "#e8590c" }]}
              xLabel="Time (s)" leftLabel={"Thrust (" + pretty(unitOf("FORCE").unit) + ")"} height={200} name="Thrust curve" />
          {/if}
          <dl>
            <dt>Total impulse</dt><dd>{fmt(chosen.totalImpulse, "IMPULSE")}</dd>
            <dt>Average / max thrust</dt><dd>{fmt(chosen.averageThrust, "FORCE")} / {fmt(chosen.maxThrust, "FORCE")}</dd>
            <dt>Burn time</dt><dd>{fmt(chosen.burnTime, "TIME", 3)}</dd>
            <dt>Mass / propellant</dt><dd>{fmt(chosen.launchMass, "MASS")} / {fmt(chosen.propellantMass, "MASS")}</dd>
            <dt>Type</dt><dd>{chosen.type.toLowerCase()}</dd>
          </dl>
          <label class="delay">Ejection delay
            <select class="select" bind:value={delay}>
              <option value="auto">Default</option>
              {#each delays as d}<option value={String(d)}>{num(d, 3)} s</option>{/each}
              <option value="plugged">Plugged</option>
            </select>
          </label>
          <button class="btn primary" onclick={use}>Use motor</button>
        {:else}
          <p class="muted">No motor selected.</p>
        {/if}
      </aside>
    </div>
  </div>
</div>

{#if custom}<CustomMotorDialog close={() => (custom = false)} created={show} />{/if}

<style>
  .own {
    display: flex;
    gap: 6px;
    margin-left: auto;
    margin-right: 6px;
  }
  .scrim {
    position: fixed;
    inset: 0;
    background: rgb(0 0 0 / 30%);
    display: grid;
    place-items: center;
    z-index: 30;
  }
  .dlg {
    width: min(1180px, calc(100vw - 40px));
    height: min(760px, calc(100vh - 40px));
    display: flex;
    flex-direction: column;
    background: var(--surface);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius);
    box-shadow: var(--shadow-pop);
    overflow: hidden;
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    padding: 10px 10px 8px 16px;
  }
  h3 {
    margin: 0;
    font-size: 15px;
  }
  .filters {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px 16px;
    padding: 0 16px 8px;
    border-bottom: 1px solid var(--line);
    color: var(--ink-2);
  }
  .filters label {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .search {
    flex: 1;
    min-width: 280px;
  }
  .search .input {
    flex: 1;
  }
  .check input {
    width: 16px;
    height: 16px;
  }
  .count {
    margin-left: auto;
  }
  .main {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: 1fr 360px;
  }
  .list {
    overflow: auto;
    min-height: 0;
  }
  .list tr {
    cursor: pointer;
  }
  .empty {
    padding: 20px;
  }
  .detail {
    border-left: 1px solid var(--line);
    padding: 14px 16px;
    overflow: auto;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  h4 {
    margin: 0;
    font-size: 16px;
  }
  dl {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 4px 12px;
    margin: 0;
    font-size: 13px;
  }
  dt {
    color: var(--ink-3);
  }
  dd {
    margin: 0;
    text-align: right;
    font-variant-numeric: tabular-nums;
  }
  .delay {
    display: flex;
    flex-direction: column;
    gap: 4px;
    font-size: 13px;
    color: var(--ink-2);
  }
</style>
