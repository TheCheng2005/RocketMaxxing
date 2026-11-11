<script lang="ts">
  import { Upload } from "@lucide/svelte";
  import { call } from "../api";
  import { run, toast } from "../state.svelte";
  import { num, pretty, unitOf, toShown } from "../units.svelte";
  import Modal from "../Modal.svelte";
  import Plot from "../Plot.svelte";

  // A motor made from the team's own data (a static fire, a research or a liquid engine): its size, masses and thrust
  // curve. It is written as a RockSim .rse in the designs folder's motors/, so OpenRocket can use it too, and it is
  // loaded again whenever the app starts.
  let { close, created }: { close: () => void; created: (designation: string) => void } = $props();

  let designation = $state("");
  let manufacturer = $state("");
  let type = $state("solid");
  let diameter = $state("");
  let length = $state("");
  let totalMass = $state("");
  let propellantMass = $state("");
  let shape = $state<"curve" | "flat">("curve");
  let curveText = $state("");
  let timeUnit = $state<"s" | "ms">("s");
  let thrustUnit = $state<"N" | "lbf" | "kgf">("N");
  let averageThrust = $state("");
  let burnTime = $state("");
  let delays = $state("");
  let dryCg = $state("");
  let propCg = $state("");
  let comment = $state("");
  let busy = $state(false);

  const TO_N = { N: 1, lbf: 4.4482216, kgf: 9.80665 };

  /** Two numbers a line (comma, semicolon, tab or space between them); header and comment lines are skipped. */
  let points = $derived.by(() => {
    const out: [number, number][] = [];
    for (const line of curveText.split(/\r?\n/)) {
      const m = line.trim().match(/^([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)\s*[,;\t ]\s*([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)/);
      if (m) out.push([Number(m[1]) / (timeUnit === "ms" ? 1000 : 1), Number(m[2]) * TO_N[thrustUnit]]);
    }
    return out;
  });

  /** Total impulse by the trapezoid rule, and the class it puts the motor in. */
  let summary = $derived.by(() => {
    if (points.length < 2) return null;
    let impulse = 0;
    for (let i = 1; i < points.length; i++) impulse += ((points[i][1] + points[i - 1][1]) / 2) * (points[i][0] - points[i - 1][0]);
    const burn = points[points.length - 1][0] - points[0][0];
    const letter = impulse <= 2.5 ? "A" : String.fromCharCode(65 + Math.min(25, Math.ceil(Math.log2(impulse / 2.5))));
    return { impulse, burn, average: burn > 0 ? impulse / burn : 0, peak: Math.max(...points.map((p) => p[1])), letter };
  });

  // A log straight from the test stand (noise, an offset, time before ignition, other columns) goes to the engine as
  // it is: it removes the offset, finds the burn and averages the noise. Offered when the data looks like one.
  let looksRaw = $derived(
    points.length > 400 || points.some((p) => p[1] < 0) || (points.length >= 2 && points[0][1] > 0.02 * Math.max(...points.map((p) => p[1]))) ||
      (curveText.trim().length > 0 && points.length < 2),
  );
  /** More than two columns: which is thrust is for the engine to find from the header, so nothing is plotted here. */
  let manyColumns = $derived(curveText.split(/\r?\n/, 40).some((l) => /^\s*[-+\d.]/.test(l) && l.trim().split(/[,;\t ]+/).length > 2));
  let raw = $state(false);
  let rawTouched = false;
  $effect(() => {
    if (!rawTouched) raw = looksRaw;
  });

  function loadFile(e: Event) {
    const f = (e.target as HTMLInputElement).files?.[0];
    if (!f) return;
    f.text().then((t) => (curveText = t));
  }

  const withUnit = (v: string, unit: string) => (/[a-z]/i.test(v) ? v.trim() : `${v.trim()} ${unit}`);

  async function save() {
    const args: Record<string, unknown> = {
      designation: designation.trim(),
      manufacturer: manufacturer.trim(),
      type,
      diameter: withUnit(diameter, unitOf("LENGTH").unit),
      length: withUnit(length, unitOf("LENGTH").unit),
      totalMass: withUnit(totalMass, unitOf("MASS").unit),
      propellantMass: withUnit(propellantMass, unitOf("MASS").unit),
    };
    if (shape === "curve" && raw) {
      args.staticFireCsv = curveText;
      // The engine reads the units from the log's header; a unit chosen here (other than the usual) says otherwise.
      if (timeUnit !== "s") args.timeUnit = timeUnit;
      if (thrustUnit !== "N") args.thrustUnit = thrustUnit;
    } else if (shape === "curve") args.thrustCurve = points;
    else {
      args.averageThrust = withUnit(averageThrust, unitOf("FORCE").unit);
      args.burnTime = withUnit(burnTime, "s");
    }
    const d = delays.split(/[,\s]+/).filter(Boolean).map(Number).filter((x) => Number.isFinite(x));
    if (d.length) args.delays = d;
    if (dryCg.trim()) args.dryCgFromTop = withUnit(dryCg, unitOf("LENGTH").unit);
    if (propCg.trim()) args.propellantCgFromTop = withUnit(propCg, unitOf("LENGTH").unit);
    if (comment.trim()) args.comment = comment.trim();
    busy = true;
    try {
      const r = await run(() => call("create_custom_motor", args));
      if (r) {
        const f = r.staticFire;
        if (f) toast(`From the log: ${f.totalImpulse}, burn ${f.burn.split(": ").pop()}, offset removed ${f.offsetRemoved}.`);
        for (const n of f?.notes ?? []) toast(n, "error");
        created(designation.trim());
        close();
      }
    } finally {
      busy = false;
    }
  }

  let ready = $derived(
    !!designation.trim() && !!manufacturer.trim() && !!diameter.trim() && !!length.trim() && !!totalMass.trim() &&
      !!propellantMass.trim() &&
      (shape === "curve" ? (raw ? curveText.trim().length > 0 : points.length >= 2) : !!averageThrust.trim() && !!burnTime.trim()),
  );
  const L = $derived(pretty(unitOf("LENGTH").unit));
  const M = $derived(pretty(unitOf("MASS").unit));
</script>

<Modal title="Your own motor" {close} width={920}>
  <div class="cols">
    <div class="form">
      <label><span>Name</span><input class="input" bind:value={designation} placeholder="e.g. Maple-M2400" /></label>
      <label><span>Maker or team</span><input class="input" bind:value={manufacturer} placeholder="e.g. UTAT" /></label>
      <label>
        <span>Type</span>
        <select class="select" bind:value={type}>
          <option value="solid">Solid</option><option value="reload">Solid, reload</option><option value="hybrid">Hybrid</option>
          <option value="liquid">Liquid (tanks and engine as one)</option>
        </select>
      </label>
      <label><span>Diameter ({L})</span><input class="input" bind:value={diameter} /></label>
      <label><span>Length ({L})</span><input class="input" bind:value={length} /></label>
      <label><span>Loaded mass ({M})</span><input class="input" bind:value={totalMass} /></label>
      <label><span>Propellant mass ({M})</span><input class="input" bind:value={propellantMass} /></label>
      <label><span>Ejection delays (s)</span><input class="input" bind:value={delays} placeholder="none: plugged" /></label>

      <div class="seg wide">
        <button class:on={shape === "curve"} onclick={() => (shape = "curve")}>Thrust curve</button>
        <button class:on={shape === "flat"} onclick={() => (shape = "flat")}>Average thrust and burn time</button>
      </div>
      {#if shape === "curve"}
        <div class="wide curve">
          <div class="bar">
            <span>Time</span>
            <select class="select" bind:value={timeUnit}><option value="s">s</option><option value="ms">ms</option></select>
            <span>Thrust</span>
            <select class="select" bind:value={thrustUnit}><option value="N">N</option><option value="lbf">lbf</option><option value="kgf">kgf</option></select>
            <label class="btn file"><Upload size={14} /> Load CSV<input type="file" accept=".csv,.txt,.tsv" onchange={loadFile} /></label>
          </div>
          <label class="check" title="For a log as the data logger wrote it: time before ignition, noise, a load cell that does not read zero, other columns.">
            <input type="checkbox" bind:checked={raw} onchange={() => (rawTouched = true)} />
            Raw test-stand log: remove the load cell's offset, find the burn (above 5% of peak), average the noise
          </label>
          <textarea rows="7" bind:value={curveText} placeholder={"time, thrust (one point a line, from the static fire)\n0, 0\n0.05, 2400\n2.9, 2100\n3.1, 0"}></textarea>
        </div>
      {:else}
        <label><span>Average thrust ({pretty(unitOf("FORCE").unit)})</span><input class="input" bind:value={averageThrust} /></label>
        <label><span>Burn time (s)</span><input class="input" bind:value={burnTime} /></label>
      {/if}
      <details class="wide">
        <summary>Centre of gravity and notes (liquids and hybrids)</summary>
        <div class="form inner">
          <label><span>Empty CG from the top ({L})</span><input class="input" bind:value={dryCg} placeholder="half the length" /></label>
          <label><span>Propellant CG from the top ({L})</span><input class="input" bind:value={propCg} /></label>
          <label class="wide"><span>Notes</span><input class="input" bind:value={comment} /></label>
        </div>
      </details>
    </div>

    <div class="preview">
      {#if shape === "curve" && raw && manyColumns}
        <p class="muted">This log has several columns. The time and thrust columns are found from its header line (names such as "time" and "thrust", "force" or "load", with the unit, e.g. "Thrust (lbf)"); the saved motor's impulse and burn time are shown when it is saved.</p>
      {:else if shape === "curve" && points.length >= 2}
        <Plot x={points.map((p) => p[0])} series={[{ label: "Thrust", values: points.map((p) => toShown(p[1], "FORCE")), color: "#e8590c" }]}
          xLabel="Time (s)" leftLabel={"Thrust (" + pretty(unitOf("FORCE").unit) + ")"} height={220} />
        {#if raw}
          <p class="muted">As logged. The saved motor has the offset removed and only the burn; its impulse and burn time are shown when it is saved.</p>
        {:else if summary}
          <dl>
            <dt>Points</dt><dd>{points.length}</dd>
            <dt>Total impulse</dt><dd>{num(toShown(summary.impulse, "IMPULSE"))} {pretty(unitOf("IMPULSE").unit)} ({summary.letter} class)</dd>
            <dt>Burn time</dt><dd>{num(summary.burn)} s</dd>
            <dt>Average / peak thrust</dt><dd>{num(toShown(summary.average, "FORCE"))} / {num(toShown(summary.peak, "FORCE"))} {pretty(unitOf("FORCE").unit)}</dd>
          </dl>
        {/if}
      {:else}
        <p class="muted">Paste or load the thrust curve to see it here.</p>
      {/if}
    </div>
  </div>
  <div class="foot">
    <span class="muted">Saved as motors/{(designation.trim() || "name").replace(/[^A-Za-z0-9._-]/g, "_")}.rse in the designs folder (the .rse keeps the mass and CG through the burn).</span>
    <button class="btn primary" disabled={!ready || busy} onclick={save}>{busy ? "Saving…" : "Add motor"}</button>
  </div>
</Modal>

<style>
  .cols {
    display: grid;
    grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
    gap: 16px;
  }
  .form {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px 10px;
    align-content: start;
  }
  .form label {
    display: flex;
    flex-direction: column;
    gap: 3px;
    font-size: 12px;
    color: var(--ink-2);
  }
  .wide {
    grid-column: 1 / -1;
  }
  .inner {
    margin-top: 8px;
  }
  .seg {
    display: flex;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    overflow: hidden;
  }
  .seg button {
    flex: 1;
    border: 0;
    background: var(--surface);
    padding: 6px 10px;
    color: var(--ink-2);
    font-size: 12.5px;
  }
  .seg button.on {
    background: var(--select);
    color: #fff;
  }
  .curve .bar {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: var(--ink-2);
    margin-bottom: 4px;
  }
  .file {
    margin-left: auto;
    position: relative;
    overflow: hidden;
  }
  .file input {
    position: absolute;
    inset: 0;
    opacity: 0;
    cursor: pointer;
  }
  textarea {
    width: 100%;
    font: 12px/1.4 ui-monospace, monospace;
    padding: 6px 8px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface);
    color: var(--ink);
    resize: vertical;
  }
  details summary {
    cursor: pointer;
    font-size: 12.5px;
    color: var(--ink-2);
  }
  dl {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 3px 10px;
    font-size: 12.5px;
  }
  dt {
    color: var(--ink-3);
  }
  dd {
    margin: 0;
  }
  .foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-top: 12px;
    font-size: 12px;
  }
  .curve .check {
    display: flex;
    flex-direction: row;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: var(--ink-2);
    margin: 2px 0 6px;
  }
</style>
