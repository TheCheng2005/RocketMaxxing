<script lang="ts">
  import { ShieldCheck, Target as TargetIcon, RefreshCw, ChevronDown, Mountain, ArrowUpFromLine, Gauge as GaugeIcon, Umbrella, Weight } from "@lucide/svelte";
  import { isOpen, toggle } from "../panels.svelte";

  // The targets in groups that fold, each with its counts, so the long list reads in parts.
  const GROUPS: { id: string; title: string; icon: typeof Mountain; ids: string[] }[] = [
    { id: "flight", title: "Flight", icon: Mountain, ids: ["apogee", "maxVelocity", "maxMach", "maxAcceleration", "timeToApogee", "flightTime"] },
    { id: "launch", title: "Off the rail", icon: ArrowUpFromLine, ids: ["railExitVelocity", "thrustToWeight"] },
    { id: "stability", title: "Stability and structure", icon: GaugeIcon, ids: ["minStability", "maxStability", "flutterMargin"] },
    { id: "recovery", title: "Recovery and landing", icon: Umbrella, ids: ["drogueDescentRate", "mainDescentRate", "groundHitVelocity", "landingDistance"] },
    { id: "mass", title: "Mass", icon: Weight, ids: ["liftoffMass"] },
  ];
  import { untrack } from "svelte";
  import { app, checkTargets, setTargets, type Target } from "../state.svelte";
  import { fmt, num, toShown, unitOf, pretty, type Dim } from "../units.svelte";
  import Panel from "../Panel.svelte";
  import RuleCheck from "../design/RuleCheck.svelte";
  import { plain } from "../issues/explain";
  import RuleCheckStatus from "../design/RuleCheckStatus.svelte";
  import Gauge from "./Gauge.svelte";
  import RocketLoader from "../RocketLoader.svelte";
  import { context, pageMenu, copyText, type MenuItem } from "../menu/menu.svelte";
  import { toast } from "../state.svelte";
  import { analysis } from "../analysis/store.svelte";

  // The team's own targets with the margin to each, next to the competition rules. Both are checked again after
  // every edit; a limit typed here is saved in the team standards file, where Claude reads the same targets.
  let onlySet = $state(false);

  let t = $derived(app.targets && app.targets.designId === app.design?.designId ? app.targets : null);
  let rows = $derived((t?.targets ?? []).filter((r) => !onlySet || r.status));
  let grouped = $derived.by(() => {
    const placed = new Set(GROUPS.flatMap((g) => g.ids));
    const all = [...GROUPS, { id: "other", title: "Other", icon: TargetIcon, ids: rows.filter((r) => !placed.has(r.id)).map((r) => r.id) }];
    return all
      .map((g) => {
        const rs = g.ids.map((id) => rows.find((r) => r.id === id)).filter((r): r is Target => !!r);
        const n = (st: string) => rs.filter((r) => r.status === st).length;
        return { ...g, rows: rs, fail: n("FAIL"), warn: n("WARN"), pass: n("PASS") };
      })
      .filter((g) => g.rows.length);
  });
  let counts = $derived.by(() => {
    const n = { FAIL: 0, WARN: 0, PASS: 0 };
    for (const r of t?.targets ?? []) if (r.status && r.status in n) n[r.status as keyof typeof n]++;
    return n;
  });

  $effect(() => {
    const id = app.design?.designId;
    if (id && untrack(() => app.targets?.designId !== id)) checkTargets();
  });

  const dimOf = (r: Target) => (r.dim ?? null) as Dim | null;
  const suffix = (r: Target) => (/Stability$/.test(r.id) ? "cal" : "");

  function shown(r: Target, si: number | null | undefined): string {
    if (si === null || si === undefined) return "–";
    const d = dimOf(r);
    return d ? fmt(si, d) : num(si) + (suffix(r) ? " " + suffix(r) : "");
  }

  function unit(r: Target): string {
    const d = dimOf(r);
    return d ? pretty(unitOf(d).unit) : suffix(r);
  }

  /** A limit as it sits in its box: a number in the chosen unit. */
  function boxed(r: Target, key: "min" | "max" | "goal"): string {
    if (r.from && key === "min") return "";
    const si = r[`${key}SI` as const];
    if (si === undefined || si === null) return "";
    const d = dimOf(r);
    return num(d ? toShown(si, d) : si);
  }

  function change(r: Target, key: "min" | "max" | "goal", text: string) {
    const v = text.trim();
    const d = dimOf(r);
    const value = v === "" ? null : /[a-z]/i.test(v) || !d ? v : `${v.replace(/,/g, "")} ${unitOf(d).unit}`;
    setTargets({ [r.id]: { [key]: value } });
  }

  // Right-click on a target: set a limit to where the design is now, clear it, copy the line.
  function now(r: Target): string | null {
    if (r.valueSI === null || r.valueSI === undefined) return null;
    const d = dimOf(r);
    return d ? `${num(toShown(r.valueSI, d)).replace(/,/g, "")} ${unitOf(d).unit}` : String(r.valueSI);
  }
  function targetItems(r: Target): MenuItem[] {
    const v = now(r);
    const set = (key: "min" | "max" | "goal") => () => setTargets({ [r.id]: { [key]: v } });
    const line = `${r.label}: ${shown(r, r.valueSI)}${r.status ? " (" + (r.status === "FAIL" ? "missed" : r.status === "WARN" ? "close" : "met") + ", " + margin(r) + ")" : ""}`;
    return [
      { header: r.label },
      { label: `Goal = now (${shown(r, r.valueSI)})`, off: !v && "No value yet", run: set("goal") },
      { label: "Minimum = now", off: !v && "No value yet", run: set("min") },
      { label: "Maximum = now", off: !v && "No value yet", run: set("max") },
      { label: "Clear this target", off: !(r.minSI ?? r.maxSI ?? r.goalSI) && !r.from && "Nothing set", danger: true, run: () => setTargets({ [r.id]: { min: null, goal: null, max: null } }) },
      "-",
      { label: "Copy this line", run: () => copyText(line).then((ok) => ok && toast("Copied")) },
      ...pageItems(),
    ];
  }
  function pageItems(): MenuItem[] {
    return [
      { label: "Check again", run: () => checkTargets() },
      { label: "Only targets set", checked: onlySet, run: () => (onlySet = !onlySet) },
      { label: "Optimizer: reach a target", run: () => ((analysis.selected = "optimize"), (app.page = "analysis")) },
      { label: "Copy all targets", off: !t?.targets && "Not checked yet", run: () => copyText((t?.targets ?? []).map((x) => `${x.label}: ${shown(x, x.valueSI)}${x.status ? " " + x.status : ""}`).join("\n")).then((ok) => ok && toast("Copied the targets")) },
    ];
  }
  pageMenu(pageItems);

  /** Margin to the nearer limit, in the chosen unit, worded. */
  function margin(r: Target): string {
    const v = r.valueSI;
    if (v === null || (r.minSI === undefined && r.maxSI === undefined)) return "";
    const toMin = r.minSI === undefined ? Infinity : v - r.minSI;
    const toMax = r.maxSI === undefined ? Infinity : r.maxSI - v;
    const atMin = toMin <= toMax;
    const m = Math.min(toMin, toMax);
    const words = atMin ? (m >= 0 ? "above min" : "below min") : m >= 0 ? "under max" : "over max";
    return `${shown(r, Math.abs(m))} ${words}`;
  }

  function fromGoal(r: Target): string {
    const d = (r.valueSI ?? 0) - (r.goalSI ?? 0);
    return `${d >= 0 ? "+" : "−"}${shown(r, Math.abs(d))} from the goal`;
  }

  function level(r: Target): string {
    return (r.status ?? "").toLowerCase();
  }
</script>

<div class="page">
  <div class="main">
    <Panel id="targets.list" icon={TargetIcon} title="Targets" fill>
      {#snippet status()}
        <span class="counts">
          {#if app.targeting}<RocketLoader size="tiny" />{/if}
          {#if t?.targets}
            <span class:bad={counts.FAIL > 0}>{counts.FAIL} missed</span>
            <span class:warn={counts.WARN > 0}>{counts.WARN} close</span>
            <span>{counts.PASS} met</span>
          {/if}
        </span>
        <button class="tool" title="Check again" onclick={() => checkTargets()}><RefreshCw size={14} /></button>
      {/snippet}
      {#snippet tools()}
        <label class="only"><input type="checkbox" bind:checked={onlySet} /> Only targets set</label>
      {/snippet}
      <div class="body" class:stale={app.targeting}>
        {#if !t}
          <RocketLoader text="Flying the design to measure it against the targets" />
        {:else if t.error}
          <p class="muted pad">{plain(t.error)}</p>
        {:else}
          {#if t.PROBLEMS?.length}
            <div class="problems">{#each t.PROBLEMS as p}<div>{p}</div>{/each}</div>
          {/if}
          <table class="grid">
            <thead>
              <tr>
                <th></th>
                <th>Target</th>
                <th class="n">Now</th>
                <th>Minimum</th>
                <th>Goal</th>
                <th>Maximum</th>
                <th></th>
                <th>Margin</th>
                <th class="g"></th>
              </tr>
            </thead>
            {#each grouped as g (g.id)}
            {@const open = isOpen("targets.group." + g.id)}
            <tbody>
              <tr class="gh">
                <td colspan="9">
                  <button class="ghb" onclick={() => toggle("targets.group." + g.id)} aria-expanded={open}>
                    <span class="gi"><g.icon size={14} /></span>
                    <span class="gt">{g.title}</span>
                    <span class="gc">
                      {#if g.fail}<span class="bad">{g.fail} missed</span>{/if}
                      {#if g.warn}<span class="warn">{g.warn} close</span>{/if}
                      {#if g.pass}<span class="good">{g.pass} met</span>{/if}
                      {#if !g.fail && !g.warn && !g.pass}<span>{g.rows.length} {g.rows.length === 1 ? "value" : "values"}, no target set</span>{/if}
                    </span>
                    <span class="chev" class:shut={!open}><ChevronDown size={14} /></span>
                  </button>
                </td>
              </tr>
              {#if open}
              {#each g.rows as r (r.id)}
                <tr class={level(r)} class:unset={!r.status} use:context={() => targetItems(r)}>
                  <td class="st {level(r)}">{r.status === "FAIL" ? "Missed" : r.status === "WARN" ? "Close" : r.status === "PASS" ? "Met" : ""}</td>
                  <td class="label">
                    {r.label}
                    {#if r.from}<div class="sub">team default</div>{/if}
                    {#if r.note}<div class="sub note">{plain(r.note)}</div>{/if}
                  </td>
                  <td class="n now">{shown(r, r.valueSI)}</td>
                  {#each ["min", "goal", "max"] as const as key (key)}
                    <td class="lim">
                      <input
                        class="input"
                        value={boxed(r, key)}
                        placeholder={r.from && key === "min" ? boxed({ ...r, from: undefined }, "min") : ""}
                        aria-label="{r.label} {key}"
                        onchange={(e) => change(r, key, (e.target as HTMLInputElement).value)}
                      />
                    </td>
                  {/each}
                  <td class="u">{unit(r)}</td>
                  <td class="margin">{margin(r)}{#if r.goalSI !== undefined && r.valueSI !== null}<div class="sub">{fromGoal(r)}</div>{/if}</td>
                  <td class="g"><Gauge value={r.valueSI} min={r.minSI} max={r.maxSI} goal={r.goalSI} level={level(r)} /></td>
                </tr>
              {/each}
              {/if}
            </tbody>
            {/each}
          </table>
          <p class="muted foot">
            Flight: {t.simulation}. A target is close when it is met by less than 5%. Targets are kept in the team standards
            file (openrocket-mcp.json in the designs folder), so Claude checks the same ones.
          </p>
        {/if}
      </div>
    </Panel>
  </div>

  <div class="side">
    <Panel id="targets.rules" icon={ShieldCheck} title="Competition rules" fill>
      {#snippet status()}<RuleCheckStatus />{/snippet}
      <RuleCheck tall />
    </Panel>
  </div>
</div>

<style>
  .page {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: minmax(0, 2fr) minmax(360px, 1fr);
    gap: var(--gap);
    padding: var(--gap);
  }
  @media (max-width: 1100px) {
    .page {
      grid-template-columns: 1fr;
      overflow: auto;
    }
  }
  .main,
  .side {
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
  .body {
    overflow: auto;
  }
  .body.stale {
    opacity: 0.75;
  }
  .pad {
    margin: 0;
    padding: 10px;
  }
  .counts {
    display: flex;
    gap: 8px;
    font-size: 12px;
    color: var(--ink-3);
    white-space: nowrap;
  }
  .bad {
    color: var(--bad);
    font-weight: 600;
  }
  .warn {
    color: var(--warn);
    font-weight: 600;
  }
  .tool {
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    border: 0;
    border-radius: 3px;
    background: transparent;
    color: var(--ink-2);
  }
  .tool:hover {
    background: var(--surface-3);
  }
  .only {
    display: flex;
    align-items: center;
    gap: 5px;
    font-size: 12px;
    color: var(--ink-2);
    white-space: nowrap;
  }
  table {
    width: 100%;
  }
  th {
    text-align: left;
    font-size: 12px;
    font-weight: 600;
    color: var(--ink-3);
    white-space: nowrap;
  }
  td {
    vertical-align: middle;
  }
  tr.unset td:not(.lim) {
    color: var(--ink-3);
  }
  tr.fail td {
    background: var(--bad-soft);
  }
  .st {
    width: 1%;
    font-weight: 600;
    white-space: nowrap;
  }
  .st.pass {
    color: var(--good);
  }
  .st.warn {
    color: var(--warn);
  }
  .st.fail {
    color: var(--bad);
  }
  .n {
    text-align: right;
    white-space: nowrap;
  }
  .now {
    font-variant-numeric: tabular-nums;
    font-weight: 600;
  }
  .lim {
    white-space: nowrap;
  }
  .lim .input {
    width: 70px;
    text-align: right;
  }
  .u {
    font-size: 12px;
    color: var(--ink-3);
    white-space: nowrap;
    padding-left: 0;
  }
  tr.gh td {
    padding: 0;
    background: var(--surface-2);
  }
  tbody tr.gh:hover td {
    background: var(--surface-3);
  }
  .ghb {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    border: 0;
    background: transparent;
    padding: 9px 12px;
    text-align: left;
    color: var(--ink);
  }
  .gi {
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    border-radius: 6px;
    background: var(--surface);
    color: var(--ink-2);
  }
  .gt {
    font-weight: 600;
  }
  .gc {
    flex: 1;
    display: flex;
    gap: 10px;
    font-size: 12px;
    color: var(--ink-3);
  }
  .gc .bad {
    color: var(--bad);
    font-weight: 600;
  }
  .gc .warn {
    color: var(--warn);
    font-weight: 600;
  }
  .gc .good {
    color: var(--good);
  }
  .chev {
    display: flex;
    color: var(--ink-3);
    transition: transform 0.1s;
  }
  .chev.shut {
    transform: rotate(-90deg);
  }
  .label {
    white-space: nowrap;
  }
  .margin {
    white-space: nowrap;
    font-size: 12.5px;
  }
  .sub {
    font-size: 11.5px;
    color: var(--ink-3);
  }
  .g {
    width: 124px;
  }
  .problems {
    margin: 8px 10px;
    padding: 6px 10px;
    border-radius: var(--radius);
    background: var(--warn-soft);
    font-size: 12.5px;
  }
  .foot {
    margin: 6px 10px 10px;
    font-size: 12px;
  }
  .sub.note {
    white-space: normal;
    overflow-wrap: anywhere;
  }
</style>
