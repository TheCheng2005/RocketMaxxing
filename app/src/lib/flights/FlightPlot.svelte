<script lang="ts">
  import { ChartLine } from "@lucide/svelte";
  import { untrack } from "svelte";
  import { call } from "../api";
  import { app, ask, toast } from "../state.svelte";
  import { choices, pretty, toShown, unitOf, type Dim, type UnitDef } from "../units.svelte";
  import Plot, { plotCsv as csvOf, type Series } from "../Plot.svelte";
  import Panel from "../Panel.svelte";
  import { context, copyText, type MenuItem } from "../menu/menu.svelte";
  import { analysis } from "../analysis/store.svelte";
  import { fd } from "../flightdata/store.svelte";

  // The Flights page's plot: any flight quantity against time or against another quantity, for the selected
  // simulation, with other simulations, each stage of a staged rocket and the measured flight on the same axes.

  interface Sim {
    index: number;
    name: string;
    status: string;
    results: Record<string, any>;
  }
  let { sims, current, plotKey }: { sims: Sim[]; current: Sim; plotKey: number } = $props();

  interface Var {
    key: string;
    name: string;
    dim?: string;
    values: (number | null)[];
  }
  /** One stage of one simulation, as the engine gives it (SI). */
  interface Flight {
    branches: string[];
    variables: Var[];
    events: { type: string; time: number }[];
  }

  let chosen = $state<string[]>(["Altitude", "Vertical velocity", "Vertical acceleration"]);
  /** What the curves are plotted against: time, or any other variable (altitude against distance, drag against Mach). */
  let xVar = $state("Time");
  type AxisChoice = "auto" | "left" | "right";
  /** Which side each variable's scale is on; unset or "auto" puts the first unit on the left and the others on the right. */
  let axes = $state<Record<string, AxisChoice>>({});
  /** A unit for one variable on this plot only (feet for altitude) in place of the app's unit. */
  let unitNames = $state<Record<string, string>>({});
  /** Other simulations drawn on the same axes. */
  let compare = $state<number[]>([]);
  /** Stages of a staged rocket left off the plot (all are drawn until one is unticked). */
  let hidden = $state<number[]>([]);
  /** The measured altitude from the Flight data page, drawn with the simulation. */
  let withLog = $state(false);
  /** The plot editor stays open while the curves are refreshed. */
  let customizing = $state(false);

  const ran = (s: Sim | undefined) => !!s && (s.status === "UPTODATE" || s.status === "LOADED" || s.status === "OUTDATED") && !!s.results?.apogee;
  let others = $derived(sims.filter((s) => s.index !== current.index && ran(s)));
  /** The simulations on the plot: the selected one first. */
  let shownSims = $derived([current, ...others.filter((s) => compare.includes(s.index))].filter(ran));

  // ------------------------------------------------------------------ data: every stage of every simulation shown
  let flights = $state<Record<string, Flight>>({});
  let loading = $state(false);
  let turn = 0;
  const key = (sim: number, branch: number) => `${sim}:${branch}`;

  /** Set when what is on screen is out of date (a new run, an edit): everything is read again, then swapped in at once. */
  let stale = false;

  async function fetchAll(want: number[], fresh: boolean, sameDesign: boolean) {
    const mine = ++turn;
    const designId = app.design?.designId;
    if (!designId) return;
    if (fresh) stale = true;
    if (!sameDesign) flights = {}; // another rocket: its curves must not stay up
    const all = stale;
    const into: Record<string, Flight> = all ? {} : flights;
    loading = true;
    try {
      for (const sim of want) {
        if (into[key(sim, 0)]) continue;
        try {
          const first = await call<Flight>("app_series", { designId, simulation: sim, branch: 0 });
          if (mine !== turn) return;
          into[key(sim, 0)] = first;
          for (let b = 1; b < first.branches.length; b++) {
            const r = await call<Flight>("app_series", { designId, simulation: sim, branch: b });
            if (mine !== turn) return;
            into[key(sim, b)] = r;
          }
        } catch {
          if (mine !== turn) return; // a flight that cannot be read is left off the plot
        }
      }
      if (all) {
        flights = into;
        stale = false;
      }
    } finally {
      if (mine === turn) loading = false;
    }
  }

  // A new run or an edited design: everything again. Another simulation picked or added: only what is missing.
  let seen = "", seenDesign = "";
  $effect(() => {
    const design = app.design?.designId ?? "";
    const stamp = `${plotKey}/${app.revision}/${design}`;
    const want = shownSims.map((s) => s.index);
    untrack(() => {
      const fresh = stamp !== seen, sameDesign = design === seenDesign;
      seen = stamp;
      seenDesign = design;
      if (!sameDesign) {
        // What was compared and hidden belonged to the other rocket.
        compare = [];
        hidden = [];
        withLog = false;
      }
      fetchAll(want, fresh, sameDesign);
    });
  });

  let primary = $derived(flights[key(current.index, 0)]);
  /** Stage names of the selected simulation (one for a single-stage rocket). */
  let stages = $derived(primary?.branches ?? []);

  /** The stages left off, never all of them (a rocket with fewer stages than the last one keeps its curve). */
  let hiddenStages = $derived.by(() => {
    const h = hidden.filter((b) => b < stages.length);
    return h.length >= stages.length ? [] : h;
  });

  /** Each curve family on the plot: a stage of a simulation, with the words that tell it from the others. */
  let sources = $derived.by(() => {
    const out: { flight: Flight; tag: string }[] = [];
    const many = shownSims.length > 1;
    const hiddenNow = hiddenStages;
    for (const s of shownSims) {
      const first = flights[key(s.index, 0)];
      if (!first) continue;
      first.branches.forEach((name, b) => {
        const f = flights[key(s.index, b)];
        if (!f || hiddenNow.includes(b)) return;
        const parts = [many ? s.name : "", first.branches.length > 1 ? name : ""].filter(Boolean);
        out.push({ flight: f, tag: parts.join(", ") });
      });
    }
    return out;
  });

  const COLORS = ["#e8590c", "#1d6fe0", "#1f9d55", "#9b51e0", "#d63384", "#0aa2c0", "#8a6420"];
  const DASHES: (number[] | undefined)[] = [undefined, [7, 4], [2, 3], [10, 3, 2, 3], [4, 2, 4, 6]];
  // Launch, first ignition and liftoff all sit at t = 0: rail exit stands for them.
  const EVENT: Record<string, string> = { IGNITION: "Ignition", LAUNCHROD: "Rail exit",
    BURNOUT: "Burnout", EJECTION_CHARGE: "Ejection", STAGE_SEPARATION: "Separation", APOGEE: "Apogee",
    RECOVERY_DEVICE_DEPLOYMENT: "Deploy", GROUND_HIT: "Landing" };

  /** The unit a variable is shown in here: the one picked for this plot, else the app's. */
  function unitFor(name: string, dim: string | undefined): UnitDef | null {
    if (!dim) return null;
    return choices(dim as Dim).find((u) => u.unit === unitNames[name]) ?? unitOf(dim as Dim);
  }
  const unitText = (v: Var) => (v.dim ? ` (${pretty(unitFor(v.name, v.dim)!.unit)})` : v.name === "Time" ? " (s)" : "");
  const shown = (v: Var) => {
    const u = unitFor(v.name, v.dim);
    return v.values.map((x) => (x === null ? null : u ? toShown(x, v.dim as Dim, u) : x));
  };

  /** The measured flight, when the Flight data page has a log for this design. */
  let log = $derived(fd.log && fd.log.designId === app.design?.designId && fd.log.series ? fd.log : null);

  let plot = $derived.by(() => {
    const main = sources[0]?.flight;
    if (!main) return null;
    const find = (f: Flight, n: string) => f.variables.find((v) => v.name === n);
    const time = find(main, "Time");
    if (!time) return null;
    const xMain = find(main, xVar) ?? time;
    const byTime = xMain === time;
    const picked = main.variables.filter((v) => chosen.includes(v.name));
    const auto = [...new Set(picked.filter((v) => (axes[v.name] ?? "auto") === "auto").map((v) => (v.dim ?? "") + unitText(v)))];
    const side = (v: Var): "left" | "right" => {
      const a = axes[v.name] ?? "auto";
      return a !== "auto" ? a : auto.indexOf((v.dim ?? "") + unitText(v)) === 0 ? "left" : "right";
    };
    const several = sources.length > 1;
    const own = several || (withLog && byTime && !!log);
    const out: Series[] = [];
    sources.forEach((src, si) => {
      const xv = find(src.flight, xMain.name), tv = find(src.flight, "Time");
      if (!xv) return;
      picked.forEach((p, vi) => {
        const v = find(src.flight, p.name);
        if (!v) return;
        // One quantity: a colour for each flight. Several: a colour for each quantity, a line pattern for each flight.
        const byFlight = picked.length === 1;
        out.push({
          label: v.name + unitText(v) + (src.tag ? ` · ${src.tag}` : ""),
          values: shown(v),
          color: COLORS[(byFlight ? si : vi) % COLORS.length],
          dash: byFlight ? undefined : DASHES[si % DASHES.length],
          axis: side(p),
          x: own ? shown(xv) : undefined,
          t: byTime ? undefined : tv?.values,
        });
      });
    });
    if (withLog && byTime && log?.series) {
      const alt = find(main, "Altitude");
      const u = unitFor("Altitude", alt?.dim ?? "DISTANCE");
      out.push({
        label: `Altitude, measured (${pretty(u?.unit ?? "m")})`,
        values: log.series.measured.map((m) => (m === null ? null : u ? toShown(m, (alt?.dim ?? "DISTANCE") as Dim, u) : m)),
        color: "#6b7280",
        dash: [2, 2],
        axis: alt && picked.includes(alt) ? side(alt) : "left",
        x: time.dim ? log.series.time.map((s) => toShown(s, time.dim as Dim, unitFor("Time", time.dim)!)) : log.series.time,
      });
    }
    // An axis is named after its one quantity; with several, after their units (the legend names the curves).
    const names = (which: "left" | "right") => {
      const all = [...new Set(out.filter((o) => (o.axis ?? "left") === which).map((o) => o.label.split(" · ")[0]))];
      if (all.length <= 1) return all[0] ?? "";
      const units = [...new Set(all.map((n) => /\(([^()]*)\)$/.exec(n)?.[1] ?? ""))];
      return units.every(Boolean) ? units.join(", ") : all.join(", ");
    };
    const xs = shown(xMain);
    const t = time.values;
    const first = out.find((o) => o.axis === "left") ?? out[0];
    return {
      x: xs,
      xLabel: xMain.name + unitText(xMain),
      series: out,
      left: names("left"),
      right: names("right"),
      events: main.events.filter((e) => EVENT[e.type] && !(e.type === "IGNITION" && e.time < 0.5)).flatMap((e) => {
        if (byTime) return [{ x: time.dim ? toShown(e.time, time.dim as Dim, unitFor("Time", time.dim)!) : e.time, label: EVENT[e.type] }];
        // Against another variable an event is a point on the first curve: where the rocket was at that moment.
        const k = t.findIndex((v) => v !== null && v >= e.time);
        const px = k < 0 ? null : xs[k];
        return px === null || px === undefined ? [] : [{ x: px, y: first?.values[k] ?? null, axis: first?.axis, label: EVENT[e.type] }];
      }),
    };
  });

  // ------------------------------------------------------------------ ready-made and saved plots
  interface Setup {
    name: string;
    x?: string;
    vars: string[];
    axes?: Record<string, AxisChoice>;
    units?: Record<string, string>;
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
  /** The units picked for this plot that differ from the app's own. */
  const unitsUsed = () =>
    Object.fromEntries(Object.entries(unitNames).filter(([n, u]) => u && (n === xVar || chosen.includes(n)) && dimOf(n) && unitOf(dimOf(n)!).unit !== u));
  const same = (p: Setup) =>
    (p.x ?? "Time") === xVar && p.vars.length === chosen.length && p.vars.every((v) => chosen.includes(v) && side(v, p.axes) === side(v, axes)) &&
    JSON.stringify(Object.entries(p.units ?? {}).sort()) === JSON.stringify(Object.entries(unitsUsed()).sort());
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
    unitNames = { ...(p.units ?? {}) };
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
    saved = [...saved.filter((p) => p.name !== name), { name, x: xVar, vars: [...chosen], axes: used, units: unitsUsed() }];
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
  const flip = (list: number[], i: number) => (list.includes(i) ? list.filter((n) => n !== i) : [...list, i]);

  let vars = $derived(primary?.variables ?? []);
  let names = $derived(vars.map((v) => v.name).filter((n) => n !== "Time"));
  /** The variables on the plot that this flight has, in the order they were added. */
  let shownVars = $derived(chosen.filter((n) => names.includes(n)));
  const dimOf = (n: string) => vars.find((v) => v.name === n)?.dim as Dim | undefined;
  const unitChoices = (n: string) => (dimOf(n) ? choices(dimOf(n)!) : []);

  function plotItems(): MenuItem[] {
    return [
      { header: "Plot" },
      ...PRESETS.map((p) => ({ label: p.name, checked: preset === p.name, run: () => show(p) })),
      ...(saved.length ? ["-" as const, ...saved.map((p) => ({ label: p.name, checked: preset === "my:" + p.name, run: () => show(p) }))] : []),
      "-",
      { label: "Save this plot…", off: !plot && "Nothing plotted", run: savePlot },
      { label: "Copy the plotted data (CSV)", off: !plot && "Nothing plotted", run: () => copyText(csvOf(plot!.xLabel, plot!.x, plot!.series)).then((ok) => ok && toast("Copied the plotted data")) },
      { label: "Export all flight data (CSV)", run: () => { analysis.selected = "export_flight_data"; app.page = "analysis"; } },
    ];
  }

  let title = $derived(`${app.design?.name ?? "Rocket"}: ${shownSims.map((s) => s.name).join(" and ")}`);
</script>

{#snippet unitPick(n: string)}
  {#if unitChoices(n).length > 1}
    <select class="select small unit" aria-label="Unit for {n}" title="The unit on this plot only"
      value={unitFor(n, dimOf(n))?.unit ?? ""} onchange={(e) => (unitNames = { ...unitNames, [n]: (e.target as HTMLSelectElement).value })}>
      {#each unitChoices(n) as u (u.unit)}<option value={u.unit}>{pretty(u.unit)}</option>{/each}
    </select>
  {/if}
{/snippet}

<Panel id="flights.plot" icon={ChartLine} title="Plot">
  {#snippet tools()}
    <select class="select small" id="plot-preset" value={preset} onchange={(e) => pick((e.target as HTMLSelectElement).value)} title="Which plot to show">
      {#if !preset}<option value="">Custom</option>{/if}
      {#each PRESETS as p (p.name)}<option value={p.name}>{p.name}</option>{/each}
      {#if saved.length}
        <optgroup label="My plots">
          {#each saved as p (p.name)}<option value={"my:" + p.name}>{p.name}</option>{/each}
        </optgroup>
      {/if}
    </select>
  {/snippet}
  <div class="plotbody" use:context={plotItems}>
    {#if plot}
      <Plot x={plot.x} series={plot.series} xLabel={plot.xLabel} leftLabel={plot.left} rightLabel={plot.right} events={plot.events} height={300}
        name={`${app.design?.name ?? "Rocket"} ${current.name} plot`} {title} />
      {#if stages.length > 1}
        <div class="pickrow" id="plot-stages">
          <span class="clabel">Stages</span>
          {#each stages as st, b (b)}
            {@const only = !hiddenStages.includes(b) && hiddenStages.length === stages.length - 1}
            <label class="tick" title={only ? "The only stage on the plot" : ""}>
              <input type="checkbox" checked={!hiddenStages.includes(b)} disabled={only} onchange={() => (hidden = flip(hiddenStages, b))} /> {st}
            </label>
          {/each}
        </div>
      {/if}
      <details class="custom" bind:open={customizing}>
        <summary>Customize the plot</summary>
        <div class="cfg">
          <div class="crow"><label class="clabel" for="plot-x">X axis</label>
            <select class="select small" bind:value={xVar} id="plot-x">
              <option value="Time">Time</option>
              {#each names as n (n)}<option value={n}>{n}</option>{/each}
            </select>
            {@render unitPick(xVar)}
          </div>
          <div class="crow top"><span class="clabel">Y axis</span>
            <div class="ys">
              {#if shownVars.length}
                <div class="yrow heads"><span class="yname">Quantity</span><span class="hu">Unit</span><span class="hs">Axis</span></div>
              {/if}
              {#each shownVars as n (n)}
                <div class="yrow">
                  <span class="yname">{n}</span>
                  {#if unitChoices(n).length > 1}{@render unitPick(n)}{:else}<span class="hu"></span>{/if}
                  <select class="select small side" value={axes[n] ?? "auto"} aria-label="Axis for {n}" title="Which side this variable's scale is on"
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
                <option value="">Add a quantity…</option>
                {#each names.filter((n) => !chosen.includes(n)) as n (n)}<option value={n}>{n}</option>{/each}
              </select>
            </div>
          </div>
          {#if others.length}
            <div class="crow top" id="plot-compare"><span class="clabel">Compare</span>
              <div class="ticks">
                {#each others as s (s.index)}
                  <label class="tick"><input type="checkbox" checked={compare.includes(s.index)} onchange={() => (compare = flip(compare, s.index))} /> {s.name}</label>
                {/each}
              </div>
            </div>
          {/if}
          {#if log}
            <div class="crow"><span class="clabel">Flight log</span>
              <label class="tick" title={xVar === "Time" ? "" : "The log is altitude against time: set the X axis to Time"}>
                <input type="checkbox" bind:checked={withLog} disabled={xVar !== "Time"} /> Measured altitude from {log.path.split(/[\\/]/).pop()}
              </label>
            </div>
          {:else}
            <div class="crow"><span class="clabel">Flight log</span>
              <span class="muted">Load an altimeter log on the <button class="link" onclick={() => (app.page = "flightdata")}>Flight data</button> page to draw the measured altitude here.</span>
            </div>
          {/if}
          <div class="crow">
            <span class="clabel"></span>
            <button class="btn" onclick={savePlot}>Save as my plot…</button>
            {#if preset.startsWith("my:")}<button class="btn ghost" onclick={() => forget(preset.slice(3))}>Delete "{preset.slice(3)}"</button>{/if}
          </div>
        </div>
      </details>
    {:else}
      <p class="muted empty">{current.results?.apogee ? (loading ? "Loading…" : "No flight data: run the simulation again.") : "Not run yet."}</p>
    {/if}
  </div>
</Panel>

<style>
  .plotbody {
    padding: 6px 10px;
    min-width: 0;
  }
  .empty {
    padding: 8px 10px;
    margin: 0;
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
  .crow, .pickrow {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .pickrow {
    flex-wrap: wrap;
    padding: 2px 0;
  }
  .crow.top {
    align-items: flex-start;
  }
  .clabel {
    flex: 0 0 64px;
    color: var(--ink-2);
    font-size: 12px;
  }
  .ys {
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
  }
  .ticks {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 18px;
    min-width: 0;
  }
  .tick {
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }
  .yrow {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .select.side {
    flex: 0 0 112px;
    width: 112px;
    min-width: 112px;
  }
  .select.unit {
    flex: 0 0 76px;
    width: 76px;
    min-width: 76px;
  }
  .heads {
    color: var(--ink-3);
    font-size: 11.5px;
  }
  .hu {
    flex: 0 0 76px;
  }
  .hs {
    flex: 0 0 112px;
  }
  .yname {
    flex: 0 0 200px;
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
  .link {
    border: 0;
    padding: 0;
    background: none;
    font: inherit;
    color: var(--accent, var(--select));
    text-decoration: underline;
    cursor: pointer;
  }
</style>
