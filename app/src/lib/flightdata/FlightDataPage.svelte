<script lang="ts">
  import { ChartSpline, Library as LibraryIcon, FolderOpen, Play } from "@lucide/svelte";
  import { call } from "../api";
  import { app, run, openPath } from "../state.svelte";
  import { analysis } from "../analysis/store.svelte";
  import { fmt, num, toShown, unitOf, pretty, type Dim } from "../units.svelte";
  import Panel from "../Panel.svelte";
  import Plot from "../Plot.svelte";
  import AccuracyChart from "./AccuracyChart.svelte";
  import RocketLoader from "../RocketLoader.svelte";
  import { fd, type Library, type LogResult } from "./store.svelte";
  import { context, pageMenu, copyText, type MenuItem } from "../menu/menu.svelte";
  import { toast } from "../state.svelte";

  // After the flight: one altimeter log against the simulation, and the team's past rockets with how well their
  // apogees were predicted.
  const CONDITIONS: [string, string, string][] = [
    ["windSpeed", "Wind", "15 km/h"],
    ["launchAngle", "Rail angle", "6 deg"],
    ["launchSiteAltitude", "Site altitude", "300 m"],
    ["temperature", "Temperature", "25 C"],
  ];

  let log = $derived(fd.log && fd.log.designId === app.design?.designId ? fd.log : null);

  async function browseLog() {
    try {
      const r = await call<{ path: string | null }>("app_choose_file", { mode: "open", types: "csv,txt" });
      if (r.path) fd.path = r.path;
    } catch {
      /* no file dialog here: the path can be typed */
    }
  }

  async function browseFolder() {
    try {
      const r = await call<{ path: string | null }>("app_choose_file", { mode: "folder" });
      if (r.path) fd.folder = r.path;
    } catch {
      /* typed instead */
    }
  }

  async function compare() {
    const d = app.design;
    if (!d || !fd.path.trim()) return;
    const args: Record<string, unknown> = { designId: d.designId, path: fd.path.trim(), configuration: d.selected };
    if (fd.altitudeUnit) args.altitudeUnit = fd.altitudeUnit;
    for (const [k] of CONDITIONS) if (fd.conditions[k].trim()) args[k] = fd.conditions[k].trim();
    fd.comparing = true;
    try {
      await run(async () => {
        const r = await call<LogResult>("app_flight_log", args);
        fd.log = { ...r, designId: d.designId, path: fd.path.trim() };
      });
    } finally {
      fd.comparing = false;
    }
  }

  async function scan() {
    fd.scanning = true;
    try {
      await run(async () => {
        fd.library = await call<Library>("app_library", fd.folder.trim() ? { folder: fd.folder.trim() } : {});
        fd.folder = fd.library.folder;
      });
    } finally {
      fd.scanning = false;
    }
  }

  function compareWith(path: string) {
    analysis.values.compare_designs = { ...(analysis.values.compare_designs ?? {}), baselinePath: path };
    analysis.selected = "compare_designs";
    app.page = "analysis";
  }

  // ------------------------------------------------------------------ right-click menus
  const reveal = (path: string) => run(() => call("app_reveal", { path }));
  function designItems(d: { path: string; name: string; predictedApogee?: number | null }): MenuItem[] {
    return [
      { header: d.name },
      { label: "Open", run: () => openPath(d.path).then(() => (app.page = "design")) },
      { label: "Compare with the open design", off: !app.design && "No design open", run: () => compareWith(d.path) },
      { label: "Show in its folder", run: () => reveal(d.path) },
      { label: "Copy its path", run: () => copyText(d.path).then((ok) => ok && toast("Copied the path")) },
      "-",
      ...pageItems(),
    ];
  }
  function pageItems(): MenuItem[] {
    return [
      { label: "Choose an altimeter log…", run: browseLog },
      { label: "Compare the log with the simulation", off: (!fd.path.trim() || fd.comparing) && "Choose a log first", run: compare },
      { label: "Choose the designs folder…", run: browseFolder },
      { label: fd.library ? "Read the folder again" : "Read the folder", off: fd.scanning && "Reading", run: scan },
      fd.library ? { label: "Show the folder", run: () => reveal(fd.library!.folder) } : null,
    ].filter(Boolean) as MenuItem[];
  }
  pageMenu(pageItems);

  let plot = $derived.by(() => {
    const s = log?.series;
    if (!s) return null;
    const show = (v: number | null) => (v === null ? null : toShown(v, "DISTANCE"));
    return {
      x: s.time,
      series: [
        { label: "Simulated", values: s.simulated.map(show), color: "#2563c9" },
        { label: "Measured", values: s.measured.map(show), color: "#d9480f" },
      ],
    };
  });

  function diff(m: number | null, s: number | null): string {
    if (m === null || s === null || s === 0) return "–";
    const p = (100 * (m - s)) / s;
    return (p >= 0 ? "+" : "−") + num(Math.abs(p), 3) + "%";
  }

  let lib = $derived(fd.library);
  let matched = $derived((lib?.flights ?? []).filter((f) => f.design && f.predictedApogee));
  let bias = $derived.by(() => {
    if (!matched.length) return null;
    const errs = matched.map((f) => (100 * ((f.predictedApogee as number) - f.apogee)) / f.apogee);
    return errs.reduce((a, b) => a + b, 0) / errs.length;
  });
  let designs = $derived([...(lib?.designs ?? [])].sort((a, b) => b.year - a.year || a.name.localeCompare(b.name)));
  let flightsOf = (file: string) => (lib?.flights ?? []).filter((f) => f.design === file);
</script>

<div class="page">
  <div class="col">
    <Panel id="flightdata.log" icon={ChartSpline} title="Flight log against the simulation" fill>
      <div class="body">
        <div class="form">
          <label class="wide">
            <span>Altimeter log (CSV of time and altitude)</span>
            <div class="file">
              <input class="input" bind:value={fd.path} placeholder="Path to the .csv" />
              <button class="btn" onclick={browseLog}><FolderOpen size={14} /> Browse…</button>
            </div>
          </label>
          <label>
            <span>Altitude unit</span>
            <select class="select" bind:value={fd.altitudeUnit}>
              <option value="">From the header</option>
              <option value="ft">ft</option>
              <option value="m">m</option>
            </select>
          </label>
          {#each CONDITIONS as [key, name, eg] (key)}
            <label>
              <span>{name} on the day</span>
              <input class="input" bind:value={fd.conditions[key]} placeholder={"e.g. " + eg} />
            </label>
          {/each}
          <div class="go">
            <button class="btn primary" disabled={!fd.path.trim() || fd.comparing} onclick={compare}>
              <Play size={14} />
              {fd.comparing ? "Flying…" : "Compare"}
            </button>
            <span class="muted">Empty conditions use the simulation's own.</span>
          </div>
        </div>

        {#if fd.comparing}<RocketLoader text="Flying the simulation in the day's conditions and fitting the drag" />{/if}
        {#if log}
          {#if plot}
            <Plot x={plot.x} series={plot.series} xLabel="Time from launch (s)" leftLabel={"Altitude (" + pretty(unitOf("DISTANCE").unit) + ")"} height={280} name="Flight log against simulation" />
          {/if}
          <table class="grid">
            <thead><tr><th></th><th class="n">Measured</th><th class="n">Simulated</th><th class="n">Difference</th></tr></thead>
            <tbody>
              {#each log.valuesSI ?? [] as v (v.quantity)}
                <tr>
                  <td>{v.quantity}</td>
                  <td class="n">{fmt(v.measured, v.dim as Dim)}</td>
                  <td class="n">{fmt(v.simulated, v.dim as Dim)}</td>
                  <td class="n">{diff(v.measured, v.simulated)}</td>
                </tr>
              {/each}
            </tbody>
          </table>
          <div class="fit">
            {#if log.dragFactorFit && log.accelerometer?.thrustFactor}
              From the apogee alone, <b>drag × {num(log.dragFactorFit, 3)}</b>; the accelerometer below separates the
              motor from the drag.
            {:else if log.dragFactorFit}
              <b>Drag × {num(log.dragFactorFit, 3)}</b> reproduces the measured apogee.
              {log.calibration?.use ?? ""}
            {:else}
              {log.calibration?.dragFactor ?? ""}
            {/if}
          </div>
          {#if log.accelerometer?.thrustFactor}
            {@const ac = log.accelerometer}
            <div class="accel">
              <b>From the accelerometer</b>
              <table class="grid">
                <tbody>
                  {#if ac.burnoutVelocity}
                    <tr><td>Burnout speed</td><td class="n">{ac.burnoutVelocity.measured}</td><td class="n">{ac.burnoutVelocity.simulated} simulated</td></tr>
                  {/if}
                  <tr><td>Thrust</td><td colspan="2">{ac.thrustFactor}</td></tr>
                  <tr><td>Drag</td><td colspan="2">{ac.dragFactor}</td></tr>
                  <tr><td>Coast drag</td><td colspan="2">{ac.coastDrag}</td></tr>
                  {#if ac.apogeeDifference}<tr><td>Apogee difference</td><td colspan="2">{ac.apogeeDifference}</td></tr>{/if}
                  {#each Object.entries(ac.climbRmsError ?? {}) as [k, v] (k)}
                    <tr><td>Climb error, {k}</td><td colspan="2">{v} RMS</td></tr>
                  {/each}
                </tbody>
              </table>
              {#if ac.reading}<p>{ac.reading} <span class="muted">{ac.caveats ?? ""}</span></p>{/if}
            </div>
          {/if}
          <ul class="notes">
            <li>Simulation: {log.simulation}</li>
            {#each log.notes ?? [] as n}<li>{n}</li>{/each}
          </ul>
        {:else}
          <p class="muted">Load the altimeter's CSV after a flight. The simulation is flown again in the day's conditions,
            and the drag that matches the measured apogee is fitted, for the next prediction. With an accelerometer
            column in the log, thrust and drag are fitted separately.</p>
        {/if}
      </div>
    </Panel>
  </div>

  <div class="col">
    <Panel id="flightdata.library" icon={LibraryIcon} title="Past rockets and their flights" fill>
      <div class="body">
        <div class="folder">
          <input class="input" bind:value={fd.folder} placeholder={app.session?.workspace ?? "Folder"} />
          <button class="btn" onclick={browseFolder}><FolderOpen size={14} /> Folder…</button>
          <button class="btn primary" disabled={fd.scanning} onclick={scan}>{fd.scanning ? "Reading…" : "Read"}</button>
        </div>
        {#if fd.scanning}<RocketLoader text="Reading every design and log in the folder" />{/if}
        {#if lib}
          <p class="summary">
            {lib.designs.length} design{lib.designs.length === 1 ? "" : "s"}, {lib.flights.length} flight log{lib.flights.length === 1 ? "" : "s"}
            ({matched.length} matched to a design).
            {#if bias !== null}
              Predictions were {bias >= 0 ? "high" : "low"} by {num(Math.abs(bias), 3)}% on average.
            {/if}
          </p>
          {#if matched.length}
            <AccuracyChart points={matched.map((f) => ({ measured: f.apogee, predicted: f.predictedApogee as number, label: `${f.year} ${f.log}` }))} />
          {/if}
          <table class="grid lib">
            <thead>
              <tr><th>Year</th><th>Design</th><th class="n">Diameter</th><th class="n">Mass</th><th>Motors</th><th class="n">Predicted</th><th class="n">Measured</th><th></th></tr>
            </thead>
            <tbody>
              {#each designs as d (d.path)}
                <tr class:err={!!d.error} use:context={() => designItems(d)}>
                  <td>{d.year}{d.yearFromPath ? "" : "*"}</td>
                  <td title={d.file}>{d.name}{#if d.error}<div class="sub">{d.error}</div>{/if}</td>
                  <td class="n">{fmt(d.diameter, "LENGTH")}</td>
                  <td class="n">{fmt(d.launchMass, "MASS")}</td>
                  <td>{d.motors?.join(", ") || "–"}</td>
                  <td class="n">{fmt(d.predictedApogee, "DISTANCE")}</td>
                  <td class="n">{flightsOf(d.file).map((f) => fmt(f.apogee, "DISTANCE")).join(", ") || "–"}</td>
                  <td class="acts">
                    <button class="link" onclick={() => openPath(d.path).then(() => (app.page = "design"))}>Open</button>
                    {#if app.design}<button class="link" onclick={() => compareWith(d.path)}>Compare</button>{/if}
                  </td>
                </tr>
              {/each}
            </tbody>
          </table>
          {#if designs.some((d) => !d.yearFromPath)}<p class="muted small">* year from the file date (put designs in year folders, e.g. 2024/).</p>{/if}
          {#if lib.flights.some((f) => !f.design)}
            <p class="muted small">Logs not matched to a design: {lib.flights.filter((f) => !f.design).map((f) => f.log).join(", ")}. Name a log after its design or keep it in the design's folder.</p>
          {/if}
        {:else}
          <p class="muted">Point at the folder of the team's past designs (.ork, .rkt; sub-folders too) and their altimeter
            logs. Every design is summarised, and each log is matched to its design to show how well apogee was predicted
            over the years.</p>
        {/if}
      </div>
    </Panel>
  </div>
</div>

<style>
  .page {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: var(--gap);
    padding: var(--gap);
  }
  @media (max-width: 1100px) {
    .page {
      grid-template-columns: 1fr;
      overflow: auto;
    }
  }
  .col {
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
  .body {
    overflow: auto;
    padding: 10px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .form {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 8px 10px;
  }
  .form label {
    display: flex;
    flex-direction: column;
    gap: 3px;
    font-size: 12px;
    color: var(--ink-2);
  }
  .form .wide {
    grid-column: 1 / -1;
  }
  .file,
  .folder {
    display: flex;
    gap: 6px;
  }
  .file .input,
  .folder .input {
    flex: 1;
    min-width: 0;
  }
  .go {
    grid-column: 1 / -1;
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 12px;
  }
  .n {
    text-align: right;
    white-space: nowrap;
  }
  th {
    text-align: left;
    font-size: 12px;
    font-weight: 600;
    color: var(--ink-3);
  }
  .fit {
    font-size: 13px;
  }
  .notes {
    margin: 0;
    padding-left: 18px;
    font-size: 12px;
    color: var(--ink-3);
  }
  .summary {
    margin: 0;
  }
  .lib td {
    font-size: 12.5px;
  }
  tr.err td {
    color: var(--ink-3);
  }
  .sub {
    font-size: 11.5px;
    color: var(--bad);
  }
  .acts {
    white-space: nowrap;
  }
  .link {
    border: 0;
    background: transparent;
    color: var(--select);
    padding: 0 4px;
  }
  .small {
    font-size: 12px;
    margin: 0;
  }
  p.muted {
    margin: 0;
  }
  .accel {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin-top: 10px;
  }
  .accel p {
    margin: 0;
    line-height: 1.5;
  }
</style>
