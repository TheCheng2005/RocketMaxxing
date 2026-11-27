<script lang="ts">
  import Splitter from "../Splitter.svelte";
  let pageEl: HTMLDivElement;
  let leftEl: HTMLDivElement;
  import { ShieldCheck, Target as TargetIcon, RefreshCw, ChevronDown, Mountain, ArrowUpFromLine, Gauge as GaugeIcon, Umbrella, Weight, Bot } from "@lucide/svelte";
  import { isOpen, toggle, sizeOf } from "../panels.svelte";

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
  import { editNum, fmt, num, readNum, toShown, unitOf, pretty, type Dim } from "../units.svelte";
  import Panel from "../Panel.svelte";
  import RuleCheck from "../design/RuleCheck.svelte";
  import { plain } from "../issues/explain";
  import RuleCheckStatus from "../design/RuleCheckStatus.svelte";
  import Gauge from "./Gauge.svelte";
  import RocketLoader from "../RocketLoader.svelte";
  import { context, pageMenu, copyText, type MenuItem } from "../menu/menu.svelte";
  import { toast } from "../state.svelte";
  import { analysis } from "../analysis/store.svelte";
  import { explain } from "../issues/explain";
  import { assistant, send, who } from "../assistant/assistant.svelte";

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
        return { ...g, rows: rs, fail: n("FAIL"), warn: n("WARN"), pass: n("PASS"), other: rs.filter((r) => r.status === "INFO" || r.invalid).length };
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

  /** Why there is no check, with what could be behind it (no motor, a configuration without a flight). */
  let failed = $derived(t?.error ? explain({ kind: "input", severity: "error", text: t.error }) : null);
  /** Targets missed or close, for the assistant button. */
  let off = $derived((t?.targets ?? []).filter((r) => r.status === "FAIL" || r.status === "WARN"));
  let missed = $derived(off.filter((r) => r.status === "FAIL"));
  let close = $derived(off.filter((r) => r.status === "WARN"));
  let canAsk = $derived(!!assistant.settings?.enabled);

  /** Asks the assistant about one target, or about every one missed; the page's numbers go with the question. */
  function askAbout(r?: Target) {
    if (!canAsk) {
      assistant.open = true; // the drawer shows how to connect it
      return;
    }
    const text = r
      ? r.status === "FAIL" || r.status === "WARN"
        ? `How can we meet the ${r.label.toLowerCase()} target? It is ${r.value} now${r.margin ? ` (${r.margin})` : ""}. Suggest design changes with numbers, smallest first; do not change the design yet.`
        : `What sets the ${r.label.toLowerCase()} (${r.value}) on this design, and what would change it most?`
      : `Which design changes would ${missed.length ? `meet the targets we miss (${missed.map((x) => x.label.toLowerCase()).join(", ")})` : ""}${missed.length && close.length ? " and " : ""}${close.length ? `give more margin on the ones that are close (${close.map((x) => x.label.toLowerCase()).join(", ")})` : ""} without breaking the others? Numbers please, smallest change first; do not change the design yet.`;
    send(text, true);
  }

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
    return editNum(d ? toShown(si, d) : si);
  }

  /** The box that was refused, so it shows as wrong until the next change. */
  let bad = $state<string | null>(null);

  async function change(r: Target, key: "min" | "max" | "goal", el: HTMLInputElement) {
    // 1,5 is a decimal comma; 9,500 a thousands separator (the box shows 10,000 for ten thousand).
    const v = readNum(el.value);
    const d = dimOf(r);
    const value = v === "" ? null : /[a-z]/i.test(v) || !d ? v : `${v} ${unitOf(d).unit}`;
    if (await setTargets({ [r.id]: { [key]: value } })) {
      bad = null;
    } else {
      // Refused (a typo, a minimum above the maximum): the box goes back to what is in effect, marked.
      el.value = boxed(r, key);
      bad = `${r.id}.${key}`;
    }
  }

  // Right-click on a target: set a limit to where the design is now, clear it, copy the line.
  function now(r: Target): string | null {
    if (r.valueSI === null || r.valueSI === undefined) return null;
    const d = dimOf(r);
    return d ? `${toShown(r.valueSI, d).toPrecision(6).replace(/\.?0+$/, "")} ${unitOf(d).unit}` : String(r.valueSI);
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
      { label: "Clear this target", off: r.minSI === undefined && r.maxSI === undefined && r.goalSI === undefined && !r.invalid && "Nothing set", danger: true, run: () => setTargets({ [r.id]: { min: null, goal: null, max: null } }) },
      "-",
      { label: r.status === "FAIL" || r.status === "WARN" ? `Ask ${who()} how to meet it` : `Ask ${who()} about it`, run: () => askAbout(r) },
      { label: "Copy this line", run: () => copyText(line).then((ok) => ok && toast("Copied")) },
      ...pageItems(),
    ];
  }
  function pageItems(): MenuItem[] {
    return [
      { label: "Check again", run: () => checkTargets() },
      { label: "Only targets set", checked: onlySet, run: () => (onlySet = !onlySet) },
      { label: "Optimizer: reach a target", run: () => ((analysis.selected = "optimize"), (app.page = "analysis")) },
      { label: `Ask ${who()} about the targets missed or close`, off: !off.length && "Nothing missed or close", run: () => askAbout() },
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

  /** Why the team's value cannot be read, without the label and the advice to come to this page. */
  function unreadable(r: Target): string {
    const t = plain(r.invalid ?? "").replace(new RegExp(`^${r.label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\s+`), "").replace(/\s*Fix it on the Targets page.*$/, "");
    return t.charAt(0).toUpperCase() + t.slice(1) + " Type a new value to replace it.";
  }

  function fromGoal(r: Target): string {
    const d = (r.valueSI ?? 0) - (r.goalSI ?? 0);
    return `${d >= 0 ? "+" : "−"}${shown(r, Math.abs(d))} from the goal`;
  }

  function level(r: Target): string {
    return (r.status ?? "").toLowerCase();
  }
</script>

<div class="page" bind:this={pageEl} style:--left={sizeOf("targets.left.w") ? `${sizeOf("targets.left.w")}px` : null}>
  <div class="main" bind:this={leftEl}>
    <Splitter id="targets.left.w" edge="end" min={420} max={() => pageEl.getBoundingClientRect().width - 380} hideBelow={1100}
      label="the targets and the rules" measure={() => leftEl.getBoundingClientRect().width} />
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
        {#if off.length}
          <button class="ask" onclick={() => askAbout()} title={canAsk ? `Ask ${who()} which changes would meet the missed targets (the numbers on this page go with the question)` : "Set up the AI assistant to ask about the missed targets"}>
            <Bot size={14} /> Ask {canAsk ? who() : "the assistant"}
          </button>
        {/if}
        <label class="only"><input type="checkbox" bind:checked={onlySet} /> Only targets set</label>
      {/snippet}
      <div class="body" class:stale={app.targeting}>
        {#if !t}
          <RocketLoader text="Flying the design to measure it against the targets" />
        {:else if failed}
          <div class="pad none">
            <div><b>Not checked.</b> {failed.text}</div>
            {#if failed.causes.length}<ul class="why">{#each failed.causes as cause}<li>{cause}</li>{/each}</ul>{/if}
            <div class="acts">
              {#if /motor/i.test(failed.text)}<button class="btn" onclick={() => (app.page = "motors")}>Choose a motor</button>{/if}
              <button class="btn" onclick={() => checkTargets()}>Check again</button>
            </div>
          </div>
        {:else}
          {#if t.PROBLEMS?.length}
            <div class="problems">{#each t.PROBLEMS as p}<div>{plain(p)}</div>{/each}</div>
          {/if}
          {#if t.unknownTargets?.length}
            <div class="problems">
              The standards file has targets this page does not know: {t.unknownTargets.join(", ")}. They are not checked; fix or
              remove them on the <button class="link" onclick={() => (app.page = "standards")}>Standards</button> page or in the file.
            </div>
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
                      {#if g.other}<span>{g.other} goal only</span>{/if}
                      {#if !g.fail && !g.warn && !g.pass && !g.other}<span>{g.rows.length} {g.rows.length === 1 ? "value" : "values"}, no target set</span>{/if}
                    </span>
                    <span class="chev" class:shut={!open}><ChevronDown size={14} /></span>
                  </button>
                </td>
              </tr>
              {#if open}
              {#each g.rows as r (r.id)}
                <tr class={level(r)} class:unset={!r.status} use:context={() => targetItems(r)}>
                  <td class="st {level(r)}" class:invalid={!!r.invalid}>{r.invalid ? "Fix" : r.status === "FAIL" ? "Missed" : r.status === "WARN" ? "Close" : r.status === "PASS" ? "Met" : r.status === "INFO" && r.goalSI !== undefined && r.minSI === undefined && r.maxSI === undefined ? "Goal" : r.status === "INFO" ? "n/a" : ""}</td>
                  <td class="label">
                    {r.label}
                    {#if r.from}<div class="sub">team default</div>{/if}
                    {#if r.invalid}<div class="sub note bad">{unreadable(r)}</div>{/if}
                    {#if r.note}<div class="sub note">{plain(r.note)}</div>{/if}
                  </td>
                  <td class="n now">{#if r.valueSI === null || r.valueSI === undefined}<span class="why">{r.value}</span>{:else}{shown(r, r.valueSI)}{/if}</td>
                  {#each ["min", "goal", "max"] as const as key (key)}
                    <td class="lim">
                      <input
                        class="input"
                        class:bad={bad === `${r.id}.${key}`}
                        value={boxed(r, key)}
                        placeholder={r.from && key === "min" ? boxed({ ...r, from: undefined }, "min") : ""}
                        aria-label="{r.label} {key}"
                        onchange={(e) => change(r, key, e.target as HTMLInputElement)}
                        onkeydown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()}
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
            Flight: {t.simulation}{#if t.stage} (numbers for the {t.stage}, the stage that reaches apogee){/if}. A target is close
            when it is met by less than 5%. Targets are kept in the team standards file (openrocket-mcp.json in the designs
            folder), so {canAsk ? who() : "the assistant"} checks and sets the same ones.
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
  .main {
    position: relative;
  }

  .page {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: var(--left, minmax(0, 2fr)) minmax(360px, 1fr);
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
    font-size: calc(12px * var(--text));
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
    font-size: calc(12px * var(--text));
    color: var(--ink-2);
    white-space: nowrap;
  }
  table {
    width: 100%;
  }
  th {
    text-align: left;
    font-size: calc(12px * var(--text));
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
    font-size: calc(12px * var(--text));
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
    font-size: calc(12px * var(--text));
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
    font-size: calc(12.5px * var(--text));
  }
  .sub {
    font-size: calc(11.5px * var(--text));
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
    font-size: calc(12.5px * var(--text));
  }
  .foot {
    margin: 6px 10px 10px;
    font-size: calc(12px * var(--text));
  }
  .sub.note {
    white-space: normal;
    overflow-wrap: anywhere;
  }
  .now .why {
    font-weight: 400;
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
    white-space: normal;
  }
  .sub.bad {
    color: var(--bad);
  }
  .st.invalid {
    color: var(--bad);
  }
  .lim .input.bad {
    border-color: var(--bad);
    box-shadow: 0 0 0 2px var(--bad-soft);
  }
  .none {
    display: flex;
    flex-direction: column;
    gap: 6px;
    color: var(--ink-2);
  }
  .none .why {
    margin: 0;
    padding-left: 18px;
    font-size: calc(12.5px * var(--text));
  }
  .none .acts {
    display: flex;
    gap: 6px;
  }
  .ask {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    height: 24px;
    padding: 0 8px;
    border: 1px solid var(--line);
    border-radius: 6px;
    background: transparent;
    color: var(--ink-2);
    font-size: calc(12px * var(--text));
    white-space: nowrap;
  }
  .ask:hover {
    color: var(--ink);
    background: var(--surface-3);
  }
  .link {
    border: 0;
    padding: 0;
    background: none;
    font: inherit;
    color: var(--select);
    text-decoration: underline;
    cursor: pointer;
  }
</style>
