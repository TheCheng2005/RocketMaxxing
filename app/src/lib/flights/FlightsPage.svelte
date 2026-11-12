<script lang="ts">
  import { ChartLine, CloudSun, Rocket, Plus, Play, PlayCircle, Trash2, Clapperboard, TriangleAlert } from "@lucide/svelte";
  import VideoDialog from "../media/VideoDialog.svelte";
  import RocketLoader from "../RocketLoader.svelte";
  import { untrack } from "svelte";
  import { call, ToolError, type IssueKind } from "../api";
  import { act, app, report, run } from "../state.svelte";
  import { fmt, num, pretty, toShown, unitOf, type Dim } from "../units.svelte";
  import FlightPlot from "./FlightPlot.svelte";
  import SimForm from "./SimForm.svelte";
  import Panel from "../Panel.svelte";
  import { context, pageMenu, copyText, type MenuItem } from "../menu/menu.svelte";
  import { ask, selectConfig, toast } from "../state.svelte";
  import { analysis } from "../analysis/store.svelte";
  import { ui, take } from "../search/ui.svelte";

  // OpenRocket's "Flight simulations" tab: the simulations with their results, the launch conditions of the
  // selected one, and its flight plotted.

  interface Sim {
    index: number;
    name: string;
    configuration: string;
    configurationName: string;
    status: string;
    options: Record<string, any>;
    results: Record<string, any>;
  }

  let sims = $state<Sim[]>([]);
  let configs = $state<{ id: string; name: string }[]>([]);
  let sel = $state(0);
  let video = $state(false);
  let running = $state(false);

  async function load() {
    if (!app.design) return;
    const r = await run(() => call<{ simulations: Sim[]; configurations: { id: string; name: string }[] }>("app_sims", { designId: app.design!.designId }));
    if (r) {
      sims = r.simulations;
      configs = r.configurations;
      if (sel >= sims.length) sel = Math.max(0, sims.length - 1);
    }
  }

  $effect(() => {
    void app.revision;
    untrack(load); // load() touches other state (the busy counter): only a new revision reloads
  });

  let current = $derived(sims[sel]);

  async function runSims(all: boolean) {
    running = true;
    const r = await act("app_sim_run", all ? {} : { simulation: sel });
    running = false;
    for (const x of r?.runs ?? []) {
      if (!x.error) continue;
      const kind = (x.errorKind ?? "system") as IssueKind;
      const text = `${x.name}: ${x.error}`;
      report(new ToolError(text, { kind, severity: "error", text }, "app_sim_run"), "Flights");
    }
    plotKey++;
  }

  async function newSim() {
    const r = await act("app_sim_new", {});
    if (r) sel = r.index;
  }

  const STATUS: Record<string, [string, string]> = {
    UPTODATE: ["good", "Up to date"],
    LOADED: ["good", "Results from the file"],
    OUTDATED: ["warn", "Out of date: the design changed since this ran"],
    NOT_SIMULATED: ["none", "Not run yet"],
    CANT_RUN: ["bad", "Cannot run"],
    EXTERNAL: ["none", "External"],
  };

  let plotKey = $state(0);

  // ------------------------------------------------------------------ right-click menus
  const toAnalysis = (tool: string) => () => {
    analysis.selected = tool;
    app.page = "analysis";
  };
  async function renameSim(s: Sim) {
    const name = await ask("Rename simulation", "Name", s.name);
    if (name?.trim()) await act("app_sim_edit", { simulation: s.index, key: "name", value: name.trim() });
  }
  function simItems(i: number): MenuItem[] {
    const s = sims[i];
    if (!s) return [];
    sel = i;
    return [
      { header: s.name },
      { label: "Run", off: running && "Running", run: () => runSims(false) },
      { label: "Video…", off: running && "Running", run: () => (video = true) },
      { label: "Rename…", run: () => renameSim(s) },
      { label: "Make its configuration active", run: () => selectConfig(s.configuration) },
      "-",
      { label: "Export flight data (CSV)", run: toAnalysis("export_flight_data") },
      { label: "Compare with an altimeter log", run: () => (app.page = "flightdata") },
      { label: "Monte Carlo dispersion", run: toAnalysis("monte_carlo") },
      "-",
      { label: "Delete", danger: true, run: () => act("app_sim_delete", { simulation: s.index }) },
    ];
  }
  $effect(() => {
    void ui.request;
    if (current && take("video")) video = true;
  });

  pageMenu(() => [
    { label: "New simulation", run: newSim },
    { label: "Run the selected one", off: (!current || running) && "Nothing to run", run: () => runSims(false) },
    { label: "Run all", off: (!sims.length || running) && "Nothing to run", run: () => runSims(true) },
    { label: "Video of the selected flight…", off: !current && "No simulation", run: () => (video = true) },
  ]);
</script>

<div class="page">
  <div class="toolbar">
    <button class="btn" onclick={newSim}><Plus size={15} /> New</button>
    <button class="btn primary" disabled={!current || running} onclick={() => runSims(false)}><Play size={15} /> Run</button>
    <button class="btn" disabled={!sims.length || running} onclick={() => runSims(true)}><PlayCircle size={15} /> Run all</button>
    <button class="btn" disabled={!current} onclick={() => current && act("app_sim_delete", { simulation: current.index })}><Trash2 size={15} /> Delete</button>
    <button class="btn" disabled={!current || running} onclick={() => (video = true)} title="The selected flight as a video"><Clapperboard size={15} /> Video</button>
    {#if running}<RocketLoader size="small" />{/if}
  </div>

  <Panel id="flights.list" icon={Rocket} title="Simulations">
    <div class="list">
      <table class="grid">
        <thead>
          <tr>
            <th></th><th>Name</th><th>Configuration</th><th>Apogee</th><th>Max speed</th><th>Max accel.</th><th>Time to apogee</th>
            <th>Flight time</th><th>Ground hit</th><th>Rail exit</th><th>Optimum delay</th>
          </tr>
        </thead>
        <tbody>
          {#each sims as s, i (s.index)}
            {@const st = STATUS[s.status] ?? ["none", s.status]}
            <tr class:sel={i === sel} onclick={() => (sel = i)} use:context={() => simItems(i)}>
              <td><span class="dot {st[0]}" title={st[1]}></span></td>
              <td>
                {s.name}
                {#if s.results.problems?.length}
                  <span class="probs" title={s.results.problems.join("\n")}><TriangleAlert size={13} /></span>
                {/if}
              </td>
              <td>{s.configurationName}</td>
              <td class="num">{fmt(s.results.apogee, "DISTANCE")}</td>
              <td class="num">{fmt(s.results.maxVelocity, "VELOCITY")}{#if s.results.maxMach}<span class="muted mach">Mach {s.results.maxMach.toFixed(2)}</span>{/if}</td>
              <td class="num">{fmt(s.results.maxAcceleration, "ACCELERATION")}</td>
              <td class="num">{fmt(s.results.timeToApogee, "TIME", 3)}</td>
              <td class="num">{fmt(s.results.flightTime, "TIME", 3)}</td>
              <td class="num">{fmt(s.results.groundHitVelocity, "VELOCITY", 3)}</td>
              <td class="num">{fmt(s.results.railExitVelocity, "VELOCITY", 3)}</td>
              <td class="num">{fmt(s.results.optimumDelay, "TIME", 2)}</td>
            </tr>
          {/each}
        </tbody>
      </table>
      {#if !sims.length}<p class="muted empty">No simulations.</p>{/if}
    </div>
  </Panel>

  {#if current}
    <div class="bottom">
      <div class="formcol">
        <Panel id="flights.conditions" icon={CloudSun} title="Launch conditions: {current.name}">
          <SimForm sim={current} {configs} />
        </Panel>
      </div>
      <FlightPlot {sims} {current} {plotKey} />
    </div>
  {/if}
</div>

{#if video && current}<VideoDialog simulation={current.index} name={current.name} close={() => (video = false)} />{/if}

<style>
  .probs {
    color: var(--warn);
    vertical-align: -2px;
    margin-left: 3px;
  }
  .page {
    flex: 1;
    min-height: 0;
    overflow: auto;
    display: flex;
    flex-direction: column;
    gap: var(--gap);
    padding: var(--gap);
  }
  .toolbar {
    display: flex;
    align-items: center;
    gap: 4px;
  }
  .list {
    max-height: 32vh;
    overflow: auto;
  }
  .list tr {
    cursor: default;
  }
  .mach {
    margin-left: 6px;
  }
  .dot {
    display: inline-block;
    width: 9px;
    height: 9px;
    border-radius: 50%;
    background: var(--line-strong);
  }
  .dot.good {
    background: var(--good);
  }
  .dot.warn {
    background: var(--warn);
  }
  .dot.bad {
    background: var(--bad);
  }
  .empty {
    padding: 8px 10px;
    margin: 0;
  }
  .bottom {
    display: flex;
    gap: 6px;
    align-items: flex-start;
    min-height: 0;
  }
  .formcol {
    width: 360px;
    flex: none;
  }
  .bottom > :global(.panel:last-child) {
    flex: 1;
  }
</style>
