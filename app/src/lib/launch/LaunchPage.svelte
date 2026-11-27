<script lang="ts">
  import Splitter from "../Splitter.svelte";
  import { sizeOf } from "../panels.svelte";
  let pageEl: HTMLDivElement;
  let leftEl: HTMLDivElement;
  import { MapPinned, Play, Save, Trash2, FolderOpen, Wind } from "@lucide/svelte";
  import { untrack } from "svelte";
  import { call, ToolError } from "../api";
  import { app, ask, confirmIt, report, run, toast } from "../state.svelte";
  import Panel from "../Panel.svelte";
  import RocketLoader from "../RocketLoader.svelte";
  import QuantityInput from "../design/QuantityInput.svelte";
  import LaunchMap from "./LaunchMap.svelte";
  import { launch, type Plan } from "./store.svelte";
  import { pageMenu } from "../menu/menu.svelte";

  // Launch day planning: the design flown hundreds of times in the ensemble weather forecast for the site and hour,
  // and where it lands, on a satellite map.
  interface Sim {
    index: number;
    name: string;
  }
  interface Saved {
    name: string;
    savedAt?: string;
    design?: string;
    time?: string;
    verdict?: string;
  }

  let sims = $state<Sim[]>([]);
  let saved = $state<Saved[]>([]);
  let running = $state(false);
  let error = $state("");

  // The days the forecast reaches (ECMWF 15, GEFS 10), from today on this computer's calendar.
  let days = $derived.by(() => {
    const out: { value: string; label: string }[] = [];
    const n = launch.model === "gfs" ? 10 : 15;
    const now = new Date();
    for (let i = 0; i < n; i++) {
      const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() + i);
      const value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
      const label = (i === 0 ? "Today, " : i === 1 ? "Tomorrow, " : "") + d.toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" });
      out.push({ value, label });
    }
    return out;
  });

  $effect(() => {
    if (!launch.day || !days.some((d) => d.value === launch.day)) launch.day = days[1]?.value ?? days[0].value;
  });

  async function load() {
    if (!app.design) return;
    const r = await call<{ simulations: Sim[] }>("app_sims", { designId: app.design.designId }).catch(() => null);
    sims = r?.simulations ?? [];
    if (!sims.some((s) => String(s.index) === launch.simulation)) launch.simulation = sims[0] ? String(sims[0].index) : "";
    if (!launch.latitude || !launch.longitude) {
      const s = await call<{ merged: { launchSite?: { latitude?: unknown; longitude?: unknown } } }>("app_standards").catch(() => null);
      const lat = s?.merged?.launchSite?.latitude, lon = s?.merged?.launchSite?.longitude;
      if (lat !== undefined && lat !== null && lat !== "" && !launch.latitude) launch.latitude = String(lat);
      if (lon !== undefined && lon !== null && lon !== "" && !launch.longitude) launch.longitude = String(lon);
    }
    refreshSaved();
  }

  async function refreshSaved() {
    const r = await call<{ plans: Saved[] }>("app_plans", {}).catch(() => null);
    saved = r?.plans ?? [];
  }

  $effect(() => {
    void app.design?.designId;
    untrack(load);
  });

  let site = $derived.by((): [number, number] | null => {
    const lat = Number(launch.latitude), lon = Number(launch.longitude);
    return launch.latitude !== "" && launch.longitude !== "" && Math.abs(lat) <= 90 && Math.abs(lon) <= 180 ? [lat, lon] : null;
  });
  let radius = $derived(Number(launch.radius));

  // A radius typed with its unit ("2 km", "1.5 mi") is turned into metres here, so the map can draw it.
  const LENGTHS: Record<string, number> = { m: 1, km: 1000, ft: 0.3048, mi: 1609.344, yd: 0.9144, nmi: 1852, in: 0.0254 };
  function setRadius(v: number | string) {
    if (typeof v === "number") return void (launch.radius = Number.isFinite(v) && v > 0 ? String(v) : "");
    const m = v.trim().replace(",", ".").match(/^([-+]?\d*\.?\d+)\s*([a-zA-Z]+)$/);
    const f = m ? LENGTHS[m[2].toLowerCase()] : undefined;
    if (m && f) launch.radius = String(Number(m[1]) * f);
    else toast(`Type the radius as a number, or with a unit: 2 km, 6000 ft, 1.5 mi (not "${v}")`, "error");
  }

  async function plan() {
    if (!app.design || running) return;
    if (!site) {
      error = "Give the launch site's latitude and longitude (decimal degrees), or set the launch site on the Standards page.";
      return;
    }
    error = "";
    running = true;
    try {
      const args: Record<string, unknown> = {
        designId: app.design.designId,
        latitude: site[0],
        longitude: site[1],
        time: `${launch.day}T${launch.hour.padStart(2, "0")}:00`,
        model: launch.model,
        runs: Number(launch.runs),
        mapData: true,
      };
      if (launch.simulation !== "") args.simulation = launch.simulation;
      if (launch.radius !== "") args.radius = launch.radius;
      launch.plan = await call<Plan>("launch_planner", args);
      launch.from = "";
    } catch (e) {
      error = e instanceof ToolError ? e.message : String(e);
      report(e);
    } finally {
      running = false;
    }
  }

  async function savePlan() {
    if (!launch.plan) return;
    const suggested = `${(launch.plan.design ?? app.design?.name ?? "plan").replace(/[^A-Za-z0-9 -]/g, "").trim().slice(0, 40)} ${launch.plan.forecast.time.replace("T", " ").replace(":", "h")}`;
    const name = await ask("Save this launch plan", "Name (kept in the designs folder's plans/)", suggested);
    if (!name) return;
    const r = await run(() => call<{ saved: string }>("app_plans", { action: "save", name, plan: launch.plan }));
    if (r) {
      toast(`Saved ${r.saved}`);
      launch.from = r.saved;
      refreshSaved();
    }
  }

  async function openPlan(p: Saved) {
    const r = await run(() => call<{ saved: { plan: Plan } }>("app_plans", { action: "load", name: p.name }));
    if (r?.saved?.plan) {
      launch.plan = r.saved.plan;
      launch.from = p.name;
      launch.latitude = String(r.saved.plan.site.latitude);
      launch.longitude = String(r.saved.plan.site.longitude);
    }
  }

  async function deletePlan(p: Saved) {
    if (!(await confirmIt("Delete this plan", `${p.name} is deleted from the plans folder.`, "Delete"))) return;
    await run(() => call("app_plans", { action: "delete", name: p.name }));
    if (launch.from === p.name) launch.from = "";
    refreshSaved();
  }

  pageMenu(() => [
    { label: "Plan the launch", off: (!app.design || running) && "Running", run: plan },
    { label: "Save this plan…", off: !launch.plan && "Nothing planned yet", run: savePlan },
  ]);

  const SUMMARY: [string, string][] = [
    ["apogee", "Apogee"],
    ["landingAsIs", "Landing (forecast as is)"],
    ["farthestLanding", "Farthest landing"],
    ["flightTime", "Flight time"],
    ["topSpeed", "Top speed"],
    ["landingSpeed", "Landing speed"],
  ];
</script>

<div class="page" bind:this={pageEl} style:--left={sizeOf("launch.left.w") ? `${sizeOf("launch.left.w")}px` : null}>
  <div class="col side" bind:this={leftEl}>
    <Panel id="launch.plan" icon={MapPinned} title="Launch planner">
      <div class="body">
        <p class="muted small">Your design flown hundreds of times in the ensemble weather forecast for the site and hour: where it lands, with the areas holding 50, 95 and 99% of the flights.</p>
        <div class="form">
          <label class="wide">Simulation
            <select class="select" bind:value={launch.simulation} disabled={!sims.length}>
              {#each sims as s (s.index)}<option value={String(s.index)}>{s.name}</option>{/each}
            </select>
          </label>
          <label>Latitude<input class="input" bind:value={launch.latitude} placeholder="48.4758" inputmode="decimal" /></label>
          <label>Longitude<input class="input" bind:value={launch.longitude} placeholder="-81.3305" inputmode="decimal" /></label>
          <label>Day
            <select class="select" bind:value={launch.day}>
              {#each days as d (d.value)}<option value={d.value}>{d.label}</option>{/each}
            </select>
          </label>
          <label>Hour (site time)
            <select class="select" bind:value={launch.hour}>
              {#each Array.from({ length: 24 }, (_, h) => String(h)) as h (h)}<option value={h}>{h.padStart(2, "0")}:00</option>{/each}
            </select>
          </label>
          <label class="wide">Forecast
            <select class="select" bind:value={launch.model}>
              <option value="ecmwf">ECMWF ensemble: 51 forecasts, winds at every height, 15 days</option>
              <option value="gfs">NOAA GEFS ensemble: 31 forecasts, 10 days</option>
            </select>
          </label>
          <label>Flights
            <select class="select" bind:value={launch.runs}>
              {#each ["100", "250", "500", "1000"] as n (n)}<option value={n}>{n}</option>{/each}
            </select>
          </label>
          <label for="launch-radius">Recovery area radius
            <QuantityInput id="launch-radius" value={launch.radius === "" || !Number.isFinite(radius) ? null : radius} dim="DISTANCE"
              commit={setRadius} />
          </label>
        </div>
        <div class="row">
          <button class="btn primary" disabled={!app.design || running} onclick={plan}><Play size={15} /> Plan the launch</button>
          <button class="btn" disabled={!launch.plan || running} onclick={savePlan}><Save size={15} /> Save plan</button>
          {#if running}<RocketLoader size="small" /><span class="muted small">Fetching the forecast and flying {launch.runs} flights…</span>{/if}
        </div>
        {#if error}<p class="error">{error}</p>{/if}

        {#if launch.plan}
          {@const p = launch.plan}
          <div class="verdict">{p.verdict}{#if launch.from}<span class="muted small"> (saved plan {launch.from})</span>{/if}</div>
          <div class="cards">
            {#each SUMMARY as [k, label] (k)}
              {#if p.summary[k]}<div class="card"><span>{label}</span><b>{p.summary[k]}</b></div>{/if}
            {/each}
          </div>
          {#if p.summary.apogeeRange}<p class="muted small">Apogee {p.summary.apogeeRange}.</p>{/if}
          {#each p.landing as a (a.branch)}
            <div class="stage">
              <b>{a.branch}</b>: lands around {a.centre}.
              {#each a.areas as r (r.percent)}<span class="ring">{r.percent}%: {r.size}</span>{/each}
              {#if a.beyondRadius}<div class="warn">{a.beyondRadius}</div>{/if}
            </div>
          {/each}
          {#if p.failedRuns > 0}<p class="warn">{p.failedRuns} of {p.runs} flights did not complete.</p>{/if}
        {/if}
      </div>
    </Panel>

    {#if launch.plan}
      <Panel id="launch.winds" icon={Wind} title="Forecast winds">
        <div class="body">
          <p class="muted small">{launch.plan.forecast.model}, {launch.plan.forecast.time.replace("T", " at ")} ({launch.plan.forecast.timezone}). {launch.plan.forecast.note} Heights above the ground; wind from.</p>
          <table>
            <thead><tr><th>Height</th><th>Speed</th><th>Ensemble range</th><th>From</th></tr></thead>
            <tbody>
              {#each launch.plan.winds as w (w.height)}<tr><td>{w.height}</td><td>{w.speed}</td><td>{w.ensembleRange}</td><td>{w.from}</td></tr>{/each}
            </tbody>
          </table>
        </div>
      </Panel>
    {/if}

    <Panel id="launch.saved" icon={FolderOpen} title="Saved plans" initial={false}>
      <div class="body">
        {#if !saved.length}<p class="muted small">None yet. Save a plan to compare days, or to take it to the field.</p>{/if}
        {#each saved as s (s.name)}
          <div class="saved">
            <button class="link" onclick={() => openPlan(s)}>{s.name.replace(/\.json$/, "")}</button>
            <span class="muted small">{s.design ?? ""}{s.time ? `, ${s.time.replace("T", " ")}` : ""}</span>
            <button class="btn ghost icon" title="Delete this plan" onclick={() => deletePlan(s)}><Trash2 size={14} /></button>
          </div>
        {/each}
      </div>
    </Panel>
  </div>

  <div class="col mapcol">
    <Splitter id="launch.left.w" edge="start" min={300} max={() => pageEl.getBoundingClientRect().width - 400} hideBelow={1000}
      label="the planner and the map" measure={() => leftEl.getBoundingClientRect().width} />
    <LaunchMap plan={launch.plan} {site} {radius} />
    {#if launch.plan}
      <p class="muted small credit">Forecast: Open-Meteo (open-meteo.com, CC BY 4.0). {launch.plan.notes[0]}</p>
    {/if}
  </div>
</div>

<style>
  .mapcol {
    position: relative;
  }

  .page {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: var(--left, minmax(340px, 420px)) minmax(0, 1fr);
    gap: var(--gap);
    padding: var(--gap);
  }
  @media (max-width: 1000px) {
    .page {
      grid-template-columns: 1fr;
      overflow: auto;
    }
  }
  .col {
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: var(--gap);
  }
  .side {
    overflow: auto;
  }
  .side > :global(*) {
    flex-shrink: 0; /* each panel its own height; the column scrolls */
  }
  .mapcol {
    min-height: 420px;
  }
  .body {
    padding: 10px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .form {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px 10px;
  }
  .form label {
    display: flex;
    flex-direction: column;
    min-width: 0;
    gap: 3px;
    font-size: calc(12px * var(--text));
    color: var(--ink-2);
  }
  .form .wide {
    grid-column: 1 / -1;
  }
  .form label > input,
  .form label > select,
  .form label > :global(.q) {
    width: 100%;
    min-width: 0;
    box-sizing: border-box;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }
  .small {
    font-size: calc(12px * var(--text));
  }
  .error {
    margin: 0;
    color: var(--bad);
  }
  .warn {
    color: var(--warn);
  }
  .verdict {
    font-weight: 600;
  }
  .cards {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 6px;
  }
  .card {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 6px 8px;
    border: 1px solid var(--line);
    border-radius: 8px;
    background: var(--surface-2, transparent);
  }
  .card span {
    color: var(--ink-3);
    font-size: calc(11px * var(--text));
  }
  .stage {
    font-size: calc(13px * var(--text));
    line-height: 1.6;
  }
  .ring {
    display: inline-block;
    margin-right: 8px;
    color: var(--ink-2);
  }
  table {
    border-collapse: collapse;
    font-size: calc(12px * var(--text));
  }
  th,
  td {
    padding: 3px 8px 3px 0;
    text-align: left;
    border-bottom: 1px solid var(--line);
    white-space: nowrap;
  }
  th {
    color: var(--ink-3);
    font-weight: 500;
  }
  .saved {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .saved .link {
    text-align: left;
  }
  .saved .small {
    flex: 1;
  }
  .credit {
    margin: 0;
  }
</style>
