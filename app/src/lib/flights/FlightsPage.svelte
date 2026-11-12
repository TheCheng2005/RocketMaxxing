<script lang="ts">
  import { ChartLine, CloudSun, Rocket, Plus, Play, PlayCircle, Trash2, Clapperboard, TriangleAlert } from "@lucide/svelte";
  import VideoDialog from "../media/VideoDialog.svelte";
  import RocketLoader from "../RocketLoader.svelte";
  import { untrack } from "svelte";
  import { call, ToolError, type IssueKind } from "../api";
  import { act, app, report, run } from "../state.svelte";
  import { fmt, num, pretty, toShown, unitOf, type Dim } from "../units.svelte";
  import Plot, { plotCsv as csvOf, type Series } from "../Plot.svelte";
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

  // ------------------------------------------------------------------ plot
  interface Var {
    key: string;
    name: string;
    dim?: string;
    values: (number | null)[];
  }
  let series = $state<{ branches: string[]; branch: number; variables: Var[]; events: { type: string; time: number }[] } | null>(null);
  let branch = $state(0);
  let chosen = $state<string[]>(["Altitude", "Vertical velocity", "Vertical acceleration"]);
  /** What the curves are plotted against: time, or any other variable (altitude against distance, drag against Mach). */
  let xVar = $state("Time");
  type AxisChoice = "auto" | "left" | "right";
  /** Which side each variable's scale is on; unset or "auto" puts the first unit on the left and the others on the right. */
  let axes = $state<Record<string, AxisChoice>>({});
  let plotKey = $state(0);

  $effect(() => {
    void plotKey;
    void app.revision;
    const s = current;
    const b = branch;
    series = null;
    if (!s || !app.design || !(s.status === "UPTODATE" || s.status === "LOADED" || s.status === "OUTDATED") || !s.results?.apogee) return;
    call("app_series", { designId: app.design.designId, simulation: s.index, branch: b })
      .then((r) => (series = r))
      .catch(() => (series = null));
  });

  const COLORS = ["#e8590c", "#1d6fe0", "#1f9d55", "#9b51e0", "#d63384", "#0aa2c0", "#8a6420"];
  // Launch, first ignition and liftoff all sit at t = 0: rail exit stands for them.
  const EVENT: Record<string, string> = { IGNITION: "Ignition", LAUNCHROD: "Rail exit",
    BURNOUT: "Burnout", EJECTION_CHARGE: "Ejection", STAGE_SEPARATION: "Separation", APOGEE: "Apogee",
    RECOVERY_DEVICE_DEPLOYMENT: "Deploy", GROUND_HIT: "Landing" };

  let plot = $derived.by(() => {
    if (!series) return null;
    const time = series.variables.find((v) => v.name === "Time");
    if (!time) return null;
    const xv = series.variables.find((v) => v.name === xVar) ?? time;
    const unit = (v: Var) => (v.name === "Time" ? " (s)" : v.dim ? ` (${pretty(unitOf(v.dim as Dim).unit)})` : "");
    const shown = (v: Var) => v.values.map((x) => (x === null ? null : v.dim ? toShown(x, v.dim as Dim) : x));
    const picked = series.variables.filter((v) => chosen.includes(v.name));
    const auto = [...new Set(picked.filter((v) => (axes[v.name] ?? "auto") === "auto").map((v) => v.dim ?? ""))];
    const side = (v: Var): "left" | "right" => {
      const a = axes[v.name] ?? "auto";
      return a !== "auto" ? a : auto.indexOf(v.dim ?? "") === 0 ? "left" : "right";
    };
    const out: Series[] = picked.map((v, i) => ({ label: v.name + unit(v), values: shown(v), color: COLORS[i % COLORS.length], axis: side(v) }));
    const names = (which: "left" | "right") => picked.filter((v) => side(v) === which).map((v) => v.name).join(", ");
    const xs = shown(xv);
    const t = time.values;
    const first = out.find((o) => o.axis === "left") ?? out[0];
    return {
      x: xs,
      xLabel: xv.name + unit(xv),
      series: out,
      left: names("left"),
      right: names("right"),
      events: series.events.filter((e) => EVENT[e.type] && !(e.type === "IGNITION" && e.time < 0.5)).flatMap((e) => {
        if (xv === time) return [{ x: e.time, label: EVENT[e.type] }];
        // Against another variable an event is a point on the first curve: where the rocket was at that moment.
        const k = t.findIndex((v) => v !== null && v >= e.time);
        const px = k < 0 ? null : xs[k];
        return px === null || px === undefined ? [] : [{ x: px, y: first?.values[k] ?? null, label: EVENT[e.type] }];
      }),
    };
  });

  interface Setup {
    name: string;
    x?: string;
    vars: string[];
    axes?: Record<string, AxisChoice>;
  }
  const PRESETS: Setup[] = [
    { name: "Vertical motion", vars: ["Altitude", "Vertical velocity", "Vertical acceleration"] },
    { name: "Total motion", vars: ["Altitude", "Total velocity", "Total acceleration"] },
    { name: "Stability", vars: ["Stability margin calibers", "CP location", "CG location"] },
    { name: "Drag", vars: ["Drag coefficient", "Mach number"] },
    { name: "Forces", vars: ["Thrust", "Drag force", "Mass"] },
    { name: "Angle of attack", vars: ["Angle of attack", "Total velocity"] },
    { name: "Flight path", x: "Lateral distance", vars: ["Altitude"] },
    { name: "Drag against Mach", x: "Mach number", vars: ["Drag coefficient"] },
  ];

  // Plots the team set up and named, kept between launches.
  const SAVED = "rocketmaxxing.plots";
  function loadSaved(): Setup[] {
    try {
      const v = JSON.parse(localStorage.getItem(SAVED) ?? "[]");
      return Array.isArray(v) ? v.filter((p) => p && typeof p.name === "string" && Array.isArray(p.vars)) : [];
    } catch {
      return [];
    }
  }
  let saved = $state<Setup[]>(loadSaved());
  function keep() {
    try {
      localStorage.setItem(SAVED, JSON.stringify(saved));
    } catch {
      /* storage unavailable: kept for this run */
    }
  }

  const side = (name: string, a?: Record<string, AxisChoice>) => a?.[name] ?? "auto";
  const same = (p: Setup) =>
    (p.x ?? "Time") === xVar && p.vars.length === chosen.length && p.vars.every((v) => chosen.includes(v) && side(v, p.axes) === side(v, axes));
  /** The built-in or saved plot on screen, as the picker's value ("my:" before a saved one's name). */
  let preset = $derived.by(() => {
    const mine = saved.find(same);
    if (mine) return "my:" + mine.name;
    return PRESETS.find(same)?.name ?? "";
  });

  function show(p: Setup) {
    xVar = p.x ?? "Time";
    chosen = [...p.vars];
    axes = { ...(p.axes ?? {}) };
  }
  function pick(value: string) {
    const p = value.startsWith("my:") ? saved.find((x) => "my:" + x.name === value) : PRESETS.find((x) => x.name === value);
    if (p) show(p);
  }
  async function savePlot() {
    const name = (await ask("Save this plot", "Name", preset.startsWith("my:") ? preset.slice(3) : ""))?.trim();
    if (!name) return;
    const used: Record<string, AxisChoice> = {};
    for (const v of chosen) if (side(v, axes) !== "auto") used[v] = axes[v];
    saved = [...saved.filter((p) => p.name !== name), { name, x: xVar, vars: [...chosen], axes: used }];
    keep();
    toast(`Saved the plot "${name}"`);
  }
  function forget(name: string) {
    saved = saved.filter((p) => p.name !== name);
    keep();
  }

  function toggleVar(name: string) {
    chosen = chosen.includes(name) ? chosen.filter((n) => n !== name) : [...chosen, name];
  }

  let names = $derived((series?.variables ?? []).map((v) => v.name).filter((n) => n !== "Time"));
  /** The variables on the plot that this flight has, in the order they were added. */
  let shownVars = $derived(chosen.filter((n) => names.includes(n)));

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
  function plotItems(): MenuItem[] {
    return [
      { header: "Plot" },
      ...PRESETS.map((p) => ({ label: p.name, checked: preset === p.name, run: () => show(p) })),
      ...(saved.length ? ["-" as const, ...saved.map((p) => ({ label: p.name, checked: preset === "my:" + p.name, run: () => show(p) }))] : []),
      "-",
      { label: "Save this plot…", off: !plot && "Nothing plotted", run: savePlot },
      { label: "Copy the plotted data (CSV)", off: !plot && "Nothing plotted", run: () => copyText(csvOf(plot!.xLabel, plot!.x, plot!.series)).then((ok) => ok && toast("Copied the plotted data")) },
      { label: "Export all flight data (CSV)", run: toAnalysis("export_flight_data") },
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
      <Panel id="flights.plot" icon={ChartLine} title="Plot">
        {#snippet tools()}
          <select class="select small" value={preset} onchange={(e) => pick((e.target as HTMLSelectElement).value)} title="Which plot to show">
            {#if !preset}<option value="">Custom</option>{/if}
            {#each PRESETS as p (p.name)}<option value={p.name}>{p.name}</option>{/each}
            {#if saved.length}
              <optgroup label="My plots">
                {#each saved as p (p.name)}<option value={"my:" + p.name}>{p.name}</option>{/each}
              </optgroup>
            {/if}
          </select>
          {#if (series?.branches.length ?? 0) > 1}
            <select class="select small" bind:value={branch}>
              {#each series!.branches as b, i}<option value={i}>{b}</option>{/each}
            </select>
          {/if}
        {/snippet}
        <div class="plotbody" use:context={plotItems}>
          {#if plot}
            <Plot x={plot.x} series={plot.series} xLabel={plot.xLabel} leftLabel={plot.left} rightLabel={plot.right} events={plot.events} height={300}
              name={`${app.design?.name ?? "Rocket"} ${current.name} plot`} title={`${app.design?.name ?? "Rocket"}: ${current.name}`} />
            <details class="custom">
              <summary>Customize the plot</summary>
              <div class="cfg">
                <label class="crow"><span class="clabel">X axis</span>
                  <select class="select small" bind:value={xVar} id="plot-x">
                    <option value="Time">Time</option>
                    {#each names as n (n)}<option value={n}>{n}</option>{/each}
                  </select>
                </label>
                <div class="crow top"><span class="clabel">Y axis</span>
                  <div class="ys">
                    {#each shownVars as n (n)}
                      <div class="yrow">
                        <span class="yname">{n}</span>
                        <select class="select small" value={axes[n] ?? "auto"} aria-label="Axis for {n}" title="Which side this variable's scale is on"
                          onchange={(e) => (axes = { ...axes, [n]: (e.target as HTMLSelectElement).value as AxisChoice })}>
                          <option value="auto">Auto</option><option value="left">Left axis</option><option value="right">Right axis</option>
                        </select>
                        <button class="x" onclick={() => toggleVar(n)} aria-label="Remove {n}" title="Remove from the plot">×</button>
                      </div>
                    {/each}
                    <select class="select small add" id="plot-add" value="" onchange={(e) => {
                      const el = e.target as HTMLSelectElement;
                      if (el.value) toggleVar(el.value);
                      el.value = "";
                    }}>
                      <option value="">Add a variable…</option>
                      {#each names.filter((n) => !chosen.includes(n)) as n (n)}<option value={n}>{n}</option>{/each}
                    </select>
                  </div>
                </div>
                <div class="crow">
                  <span class="clabel"></span>
                  <button class="btn" onclick={savePlot}>Save as my plot…</button>
                  {#if preset.startsWith("my:")}<button class="btn ghost" onclick={() => forget(preset.slice(3))}>Delete "{preset.slice(3)}"</button>{/if}
                </div>
              </div>
            </details>
          {:else}
            <p class="muted empty">{current.results?.apogee ? "Loading…" : "Not run yet."}</p>
          {/if}
        </div>
      </Panel>
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
  .plotbody {
    padding: 6px 10px;
    min-width: 0;
  }
  .select.small {
    height: 24px;
    font-size: 12px;
  }
  details {
    margin-top: 4px;
  }
  summary {
    cursor: pointer;
    color: var(--ink-2);
  }
  .cfg {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 8px 0 4px;
  }
  .crow {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .crow.top {
    align-items: flex-start;
  }
  .clabel {
    flex: 0 0 52px;
    color: var(--ink-2);
    font-size: 12px;
  }
  .ys {
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
  }
  .yrow {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .yrow .select {
    flex: 0 0 112px;
    width: 112px;
    min-width: 112px;
  }
  .yname {
    flex: 0 1 220px;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .x {
    width: 22px;
    height: 22px;
    border: 0;
    border-radius: 4px;
    background: transparent;
    color: var(--ink-2);
    font-size: 15px;
    line-height: 1;
    cursor: pointer;
  }
  .x:hover {
    background: color-mix(in srgb, var(--ink-3) 15%, transparent);
    color: var(--ink);
  }
  .add {
    align-self: flex-start;
  }
</style>
