<script lang="ts">
  import { untrack } from "svelte";
  import { FileUp, Trash2, ChartLine, Info } from "@lucide/svelte";
  import { call } from "../api";
  import { app, act, ask } from "../state.svelte";
  import { fmt, num, type Dim } from "../units.svelte";
  import { analysis } from "../analysis/store.svelte";
  import { context, copyText, type MenuItem } from "../menu/menu.svelte";
  import { toast } from "../state.svelte";
  import Plot from "../Plot.svelte";
  import Tip from "../Tip.svelte";

  // Transonic and supersonic flights: OpenRocket's numbers beside RASAero II's, from an aero table the team exports
  // from RASAero and imports here. Without a table, what it would take to get one.
  interface Flight {
    apogee?: number;
    maxVelocity?: number;
    maxMach?: number;
    timeToApogee?: number;
    railExitVelocity?: number;
    minStability?: number;
    minStabilityMach?: number;
  }
  interface Row {
    mach: number;
    cdOpenRocket?: number | null;
    cpOpenRocket?: number | null;
    cdRasaeroPowerOff?: number | null;
    cdRasaeroPowerOn?: number | null;
    cpRasaero?: number | null;
  }
  interface Cmp {
    hasTable: boolean;
    source?: string;
    useDrag?: boolean;
    hasCp?: boolean;
    tableMaxMach?: number;
    openRocket?: Flight;
    rasaero?: Flight;
    maxMach: number | null;
    byMach: Row[];
    referenceDiameter: number;
  }

  let data = $state<Cmp | null>(null);
  let loading = $state(false);
  let seq = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;

  // After each edit, a moment later (the comparison flies the rocket twice when a table is in).
  $effect(() => {
    void app.revision;
    const id = app.design?.designId;
    untrack(() => {
      clearTimeout(timer);
      timer = setTimeout(() => load(id), data ? 700 : 0);
    });
    return () => clearTimeout(timer);
  });

  async function load(id: string | undefined) {
    if (!id) return;
    const s = ++seq;
    loading = true;
    try {
      const r = await call<Cmp>("app_aero_compare", { designId: id });
      if (s === seq) data = r;
    } catch {
      if (s === seq) data = null;
    } finally {
      if (s === seq) loading = false;
    }
  }

  async function importTable() {
    let path: string | null = null;
    try {
      path = (await call<{ path: string | null }>("app_choose_file", { mode: "open", types: "csv,txt" })).path;
    } catch {
      path = await ask("Import RASAero data", "CSV exported from RASAero II (Mach, CD, CP...)");
    }
    if (path?.trim()) await act("import_aero_table", { path: path.trim() });
  }
  const clear = () => act("import_aero_table", { mode: "clear" });
  function fullAnalysis() {
    analysis.selected = "aero_analysis";
    app.page = "analysis";
  }

  const ROWS: [keyof Flight, string, Dim | "cal" | ""][] = [
    ["apogee", "Apogee", "DISTANCE"],
    ["maxVelocity", "Top speed", "VELOCITY"],
    ["maxMach", "Top Mach", ""],
    ["timeToApogee", "Time to apogee", "TIME"],
    ["railExitVelocity", "Rail exit", "VELOCITY"],
    ["minStability", "Lowest stability", "cal"],
  ];
  function show(v: number | undefined | null, d: Dim | "cal" | ""): string {
    if (v === undefined || v === null) return "–";
    if (d === "cal") return num(v, 3) + " cal";
    if (d === "") return num(v, 3);
    return fmt(v, d);
  }
  function diff(a: number | undefined | null, b: number | undefined | null, d: Dim | "cal" | ""): { text: string; big: boolean } {
    if (a == null || b == null) return { text: "", big: false };
    if (d === "cal") return { text: `${b - a >= 0 ? "+" : "−"}${num(Math.abs(b - a), 2)} cal`, big: Math.abs(b - a) >= 0.5 };
    if (a === 0) return { text: "", big: false };
    const p = ((b - a) / Math.abs(a)) * 100;
    return { text: `${p >= 0 ? "+" : "−"}${num(Math.abs(p), 2)}%`, big: Math.abs(p) >= 5 };
  }
  const len = (v: number | null | undefined) => (v == null ? "–" : fmt(v, "LENGTH"));
  const transonic = (m: number) => m >= 0.8 && m <= 1.2;

  let plot = $derived.by(() => {
    const rows = data?.byMach ?? [];
    if (rows.length < 2) return null;
    const x = rows.map((r) => r.mach);
    const series = [{ label: "CD OpenRocket", values: rows.map((r) => r.cdOpenRocket ?? null), color: "#2563c9" }];
    if (data?.hasTable) {
      series.push({ label: "CD RASAero (power off)", values: rows.map((r) => r.cdRasaeroPowerOff ?? null), color: "#d9480f" });
      series.push({ label: "CD RASAero (power on)", values: rows.map((r) => r.cdRasaeroPowerOn ?? null), color: "#f59f00" });
    }
    return { x, series, events: data?.maxMach ? [{ x: data.maxMach, label: "Top speed" }] : [] };
  });

  function items(): MenuItem[] {
    const lines = data?.openRocket
      ? ROWS.map(([k, l, d]) => `${l}: OpenRocket ${show(data!.openRocket?.[k], d)}${data!.hasTable ? `, RASAero ${show(data!.rasaero?.[k], d)}` : ""}`)
      : [];
    return [
      { header: "RASAero vs OpenRocket" },
      { label: data?.hasTable ? "Replace the RASAero table…" : "Import RASAero data…", run: importTable },
      { label: "Remove the RASAero table", off: !data?.hasTable && "No table imported", danger: true, run: clear },
      { label: "Copy the comparison", off: !lines.length && "No flight yet", run: () => copyText(lines.join("\n")).then((ok) => ok && toast("Copied the comparison")) },
      { label: "Stability and drag vs Mach (Analysis)", run: fullAnalysis },
    ];
  }
</script>

<div class="ac" class:stale={loading} use:context={items}>
  {#if !data}
    <p class="muted pad">Comparing…</p>
  {:else if !data.hasTable}
    <div class="ask">
      <Info size={16} />
      <div>
        <p>
          {#if data.maxMach}This flight reaches <b>Mach {num(data.maxMach, 3)}</b>.{/if}
          OpenRocket's drag and centre of pressure are least sure between Mach 0.8 and 1.2, so teams check them against
          RASAero II (Launch Canada asks for RASAero CP and CD when the airframe's diameter changes). Import RASAero's aero
          data to see both side by side here, in the flights and in the rule check.
          <Tip label="How to get the file from RASAero II">
            In RASAero II, open the rocket, run the aerodynamic analysis and export the aero data to a CSV file (columns
            Mach, Alpha, CD, CD Power-Off, CD Power-On, CP; CP in inches from the nose tip). Import that file here. On a staged
            rocket, import a run of the whole stack; the sustainer's own table goes in from Analysis › RASAero aero table.
          </Tip>
        </p>
        <div class="acts">
          <button class="btn primary" onclick={importTable}><FileUp size={15} /> Import RASAero CSV…</button>
          <button class="btn" onclick={fullAnalysis}><ChartLine size={15} /> OpenRocket's drag and CP vs Mach</button>
        </div>
      </div>
    </div>
    {#if plot}<div class="plot"><Plot x={plot.x} series={plot.series} xLabel="Mach" leftLabel="CD" events={plot.events} height={180} name="Drag against Mach" /></div>{/if}
  {:else}
    <div class="src">
      <span><b>RASAero table:</b> {data.source}{#if data.tableMaxMach}{` (to Mach ${num(data.tableMaxMach, 3)})`}{/if}</span>
      <span class="muted">
        {data.useDrag ? "The app's flights use RASAero's drag; the OpenRocket column is the same flight on OpenRocket's own drag." : "Imported without its drag: both columns are OpenRocket's flight, RASAero gives the CP."}
      </span>
    </div>
    <table class="grid cmp">
      <thead><tr><th></th><th class="n">OpenRocket</th><th class="n">RASAero</th><th class="n">Difference</th></tr></thead>
      <tbody>
        {#each ROWS as [k, l, d] (k)}
          {@const a = data.openRocket?.[k]}
          {@const b = data.rasaero?.[k]}
          {@const df = diff(a, b, d)}
          <tr>
            <td>{l}{#if k === "minStability" && data.rasaero?.minStabilityMach}<span class="muted small">{` at Mach ${num(data.rasaero.minStabilityMach, 2)}`}</span>{/if}</td>
            <td class="n">{show(a, d)}</td>
            <td class="n">{k === "minStability" && !data.hasCp ? "no CP in table" : show(b, d)}</td>
            <td class="n" class:big={df.big}>{df.text}</td>
          </tr>
        {/each}
      </tbody>
    </table>
    {#if plot}<div class="plot"><Plot x={plot.x} series={plot.series} xLabel="Mach" leftLabel="CD" events={plot.events} height={180} name="Drag against Mach" /></div>{/if}
    <details class="bym">
      <summary>CD and CP by Mach</summary>
      <table class="grid">
        <thead>
          <tr>
            <th class="n">Mach</th><th class="n">CD OpenRocket</th><th class="n">CD RASAero off / on</th><th class="n">CP OpenRocket</th><th class="n">CP RASAero</th>
          </tr>
        </thead>
        <tbody>
          {#each data.byMach as r (r.mach)}
            <tr class:tr={transonic(r.mach)}>
              <td class="n">{num(r.mach, 3)}</td>
              <td class="n">{r.cdOpenRocket == null ? "–" : num(r.cdOpenRocket, 3)}</td>
              <td class="n">{r.cdRasaeroPowerOff == null ? "–" : `${num(r.cdRasaeroPowerOff, 3)} / ${num(r.cdRasaeroPowerOn ?? NaN, 3)}`}</td>
              <td class="n">{len(r.cpOpenRocket)}</td>
              <td class="n">{len(r.cpRasaero)}</td>
            </tr>
          {/each}
        </tbody>
      </table>
      <p class="muted small">CP from the nose tip. Shaded rows: Mach 0.8 to 1.2, where the two models differ most.</p>
    </details>
    <div class="acts">
      <button class="btn" onclick={importTable}><FileUp size={15} /> Replace…</button>
      <button class="btn" onclick={clear}><Trash2 size={15} /> Remove</button>
      <button class="btn" onclick={fullAnalysis}><ChartLine size={15} /> Full analysis</button>
    </div>
  {/if}
</div>

<style>
  .ac {
    padding: 12px 14px 14px;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  .ac.stale {
    opacity: 0.7;
  }
  .pad {
    margin: 0;
  }
  .ask {
    display: flex;
    gap: 10px;
    padding: 10px 12px;
    border-radius: 8px;
    background: var(--warn-soft);
  }
  .ask :global(svg) {
    flex: none;
    margin-top: 2px;
    color: var(--warn);
  }
  .ask p {
    margin: 0 0 10px;
    line-height: 1.5;
  }
  .acts {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
  .src {
    display: flex;
    flex-direction: column;
    gap: 2px;
    font-size: 12.5px;
  }
  .cmp td:first-child {
    font-weight: 500;
  }
  .n {
    text-align: right;
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }
  td.big {
    color: var(--warn);
    font-weight: 600;
  }
  tr.tr td {
    background: var(--warn-soft);
  }
  .bym summary {
    cursor: pointer;
    font-weight: 600;
    padding: 4px 0;
  }
  .small {
    font-size: 12px;
  }
  .plot {
    min-height: 180px;
  }
</style>
