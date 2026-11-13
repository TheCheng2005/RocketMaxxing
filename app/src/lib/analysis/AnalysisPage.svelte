<script lang="ts">
  import { ClipboardList, Compass, ListChecks, SlidersHorizontal, Play, ChevronDown, Search, X, ShieldCheck, Gauge, Umbrella, Wrench, Radio, FlaskConical, GitCompare, FileText } from "@lucide/svelte";

  /** An icon per group of analyses. */
  const GROUP_ICONS: Record<string, typeof ShieldCheck> = {
    Checks: ShieldCheck, "Stability and drag": Gauge, Recovery: Umbrella, Structures: Wrench, "Launch and avionics": Radio,
    Studies: FlaskConical, "Compare and import": GitCompare, Files: FileText,
  };
  import { untrack } from "svelte";
  import { call, callFull, tools as listTools, type ToolInfo } from "../api";
  import { app, refresh, run, type TreeNode } from "../state.svelte";
  import { isOpen, toggle } from "../panels.svelte";
  import Panel from "../Panel.svelte";
  import Result from "./Result.svelte";
  import ResultPlot from "./ResultPlot.svelte";
  import RowsEditor from "./RowsEditor.svelte";
  import StartHere from "./StartHere.svelte";
  import { CATALOG, LONG_INPUTS, OVERRIDES, PART_INPUTS, type Analysis } from "./catalog";
  import { analysis } from "./store.svelte";
  import RocketLoader from "../RocketLoader.svelte";
  import { firstSentence, label } from "./text";
  import { KINDS, openIssues } from "../issues/issues.svelte";
  import { plain } from "../issues/explain";
  import Tip from "../Tip.svelte";
  import { MAC } from "../keys";
  import { GUIDES, needsFlight, type Guide } from "./guides";
  import { bareUnit, example, fieldHelp, type FieldHelp } from "./fields";
  import { units } from "../units.svelte";
  import { search, type Entry } from "../search/search";
  import { ANALYSIS_KEYS, analysisText } from "../search/entries";
  import { context, pageMenu, copyText, type MenuItem } from "../menu/menu.svelte";
  import { toast } from "../state.svelte";
  import { ui } from "../search/ui.svelte";
  import { setOpen } from "../panels.svelte";

  // The engine's analyses: pick one, fill in what it needs (the design is filled in), run, read the result.

  let infos = $state<Record<string, ToolInfo>>({});
  let sims = $state<string[]>([]);
  let designs = $state<{ designId: string; name: string }[]>([]);

  listTools().then((list) => (infos = Object.fromEntries(list.map((t) => [t.name, t]))));

  // The team's shear pins, so a pin type is chosen from the ones the standards rate rather than typed.
  let pins = $state<string[]>([]);
  call<{ merged: any }>("app_standards")
    .then((s) => (pins = Object.keys(s.merged?.recovery?.shearPins ?? {})))
    .catch(() => {});
  let imperial = $derived(units.system === "imperial");

  $effect(() => {
    void app.revision;
    untrack(async () => {
      if (!app.design) return;
      const r = await call<{ simulations: { name: string }[] }>("app_sims", { designId: app.design.designId }).catch(() => null);
      sims = r?.simulations.map((s) => s.name) ?? [];
      const ses = await call<{ designs: { designId: string; name: string }[] }>("app_session").catch(() => null);
      designs = ses?.designs.filter((x) => x.designId !== app.design?.designId) ?? [];
    });
  });

  let entry = $derived(CATALOG.flatMap((g) => g.items).find((a) => a.tool === analysis.selected) ?? CATALOG[0].items[0]);
  let info = $derived(infos[entry.tool]);
  let guide = $derived(GUIDES[entry.tool]);
  /** The Start here overview instead of an analysis. */
  let starting = $derived(analysis.selected === "start");
  let outcome = $derived(analysis.results[entry.tool]);
  let failure = $derived(analysis.failures[entry.tool]);
  let stale = $derived(!!outcome && (outcome.designId !== app.design?.designId || outcome.revision < app.revision));
  let running = $state(false);

  type Kind = "sim" | "config" | "part" | "pin" | "design" | "file" | "rows" | "enum" | "qty" | "bool" | "number" | "list" | "text" | "long";

  function kind(key: string, s: any): Kind | null {
    if (key === "designId") return null;
    if (key === "simulation") return "sim";
    if (key === "configuration") return "config";
    if (key === "baselineDesignId") return "design";
    if (entry.files?.[key]) return "file";
    if (PART_INPUTS[key]) return "part";
    if (key === "pinType" && pins.length) return "pin";
    if (s.enum) return "enum";
    if (Array.isArray(s.type)) return "qty";
    if (s.type === "boolean") return "bool";
    if (s.type === "number" || s.type === "integer") return "number";
    if (s.type === "array") {
      if (s.items?.type === "object") return s.items.properties ? "rows" : null;
      return s.items ? "list" : null;
    }
    if (s.type === "string") return LONG_INPUTS.includes(key) ? "long" : "text";
    return null; // objects and lists of objects need their own forms (tier 2)
  }

  interface Input {
    key: string;
    kind: Kind;
    schema: any;
    required: boolean;
    /** One of the analysis's either-or inputs (at least one of them is needed). */
    oneOf: boolean;
  }

  let inputs = $derived.by(() => {
    const props = info?.inputSchema.properties ?? {};
    const req = new Set(info?.inputSchema.required ?? []);
    const either = new Set(entry.oneOf ?? []);
    const all: Input[] = [];
    // Inputs that go with another input's choice (an engine type's own) show for that choice only.
    const when = entry.when;
    const chosen = when ? analysis.values[entry.tool]?.[when.key] || (props[when.key] as any)?.enum?.[0] : undefined;
    for (const [key, schema] of Object.entries(props)) {
      if (when?.fields[key] && !when.fields[key].includes(chosen)) continue;
      const k = kind(key, schema);
      if (k) all.push({ key, kind: k, schema, required: req.has(key), oneOf: either.has(key) });
    }
    const main = entry.main.map((k) => all.find((i) => i.key === k)).filter((i): i is Input => !!i);
    for (const i of all) if (i.required && !main.includes(i)) main.unshift(i);
    const conditions = all.filter((i) => OVERRIDES.includes(i.key) && !main.includes(i));
    const more = all.filter((i) => !main.includes(i) && !conditions.includes(i));
    return { main, conditions, more };
  });

  function fileName() {
    return (app.design?.name ?? "design").replace(/[^A-Za-z0-9._-]+/g, "_");
  }

  /** Each analysis keeps its own inputs, starting from its defaults. */
  function ensure(a: Analysis) {
    analysis.values[a.tool] ??= { ...(a.defaults?.(fileName()) ?? {}) };
  }
  untrack(() => ensure(entry)); // the analysis shown first
  let values = $derived(analysis.values[entry.tool]);

  // A required part input starts on the first part that fits (the only parachute, the motor mount).
  $effect(() => {
    for (const i of inputs.main) {
      if (i.kind === "part" && i.required && !untrack(() => values[i.key])) {
        const first = partsFor(i.key)[0];
        if (first) untrack(() => (values[i.key] = first.id));
      }
    }
  });

  /** The filled-in rows of a list input. */
  function filledRows(raw: string): Record<string, string>[] {
    try {
      const r = JSON.parse(raw || "[]");
      return Array.isArray(r) ? r.filter((row) => Object.values(row ?? {}).some((v) => String(v ?? "").trim() !== "")) : [];
    } catch {
      return [];
    }
  }

  function filled(i: Input): boolean {
    return i.kind === "rows" ? filledRows(values[i.key] ?? "").length > 0 : !!(values[i.key] ?? "").trim();
  }

  /** The either-or inputs this analysis has, as "Bay tube, Bay diameter or Bay volume". */
  let eitherList = $derived.by(() => {
    const names = [...inputs.main, ...inputs.more].filter((i) => i.oneOf).map((i) => label(i.key));
    return names.length > 1 ? names.slice(0, -1).join(", ") + " or " + names[names.length - 1] : names.join("");
  });

  // The configuration the analysis will fly when no simulation is named: the one chosen here, else the active one.
  let flown = $derived.by(() => {
    const d = app.design;
    if (!d || (values?.simulation ?? "").trim()) return null;
    const id = (values?.configuration ?? "").trim() || d.selected;
    // OpenRocket's own placeholder configuration (a new rocket's) is not in the list and never has a motor.
    return d.configurations.find((c) => c.id === id) ?? { id, name: "[No motors]", hasMotors: false };
  });
  let noMotor = $derived(needsFlight(entry.tool) && !!flown && !flown.hasMotors);

  let missing = $derived.by(() => {
    const out = inputs.main.filter((i) => i.required && !filled(i)).map((i) => label(i.key));
    const either = [...inputs.main, ...inputs.more].filter((i) => i.oneOf);
    if (either.length && !either.some(filled)) out.push(eitherList);
    // Inputs a choice elsewhere in the form calls for (the file, when the mode is import).
    const all = [...inputs.main, ...inputs.conditions, ...inputs.more];
    for (const rule of entry.needIf ?? []) {
      if (!rule.is.includes((values[rule.key] ?? "").trim())) continue;
      const need = rule.need.map((k) => all.find((i) => i.key === k)).filter((i): i is Input => !!i);
      if (need.length && !need.some(filled)) out.push(need.map((i) => label(i.key)).join(" or "));
    }
    if (noMotor) out.push("a motor");
    return out;
  });

  /** The engine's description of an input, without the unit sentence every quantity carries. */
  function described(i: Input): string {
    return (i.schema.description ?? "").replace(/\s*Number = SI units, or a string with units, e\.g\. "20 ft\/s"\./, "").trim();
  }

  /** What the engine uses when the input is left empty, from its description. */
  function whenEmpty(i: Input): string {
    const d = described(i);
    const m = d.match(/\(default:? ([^)]+)\)/i) ?? d.match(/Default: ([^.]+)\./);
    return m ? m[1].trim() : "";
  }

  function quantities(i: Input): boolean {
    return i.kind === "qty" || (i.kind === "list" && Array.isArray(i.schema.items?.type));
  }

  function placeholder(i: Input): string {
    const ex = example(fieldHelp(entry.tool, i.key), imperial);
    if (ex) return "e.g. " + ex;
    if (i.kind === "qty") return "number and unit";
    if (i.kind === "list") return "comma separated";
    return "";
  }

  /** A number typed without a unit gets the field's unit (a bare 270 for a direction is 270 deg, not radians). */
  function withUnit(i: Input, x: string): string | number {
    if (!NUMBER.test(x)) return x;
    const u = bareUnit(fieldHelp(entry.tool, i.key), imperial);
    return u ? `${x} ${u}` : Number(x);
  }

  function flatten(n: TreeNode | undefined, out: TreeNode[] = []): TreeNode[] {
    if (!n) return out;
    if (n.id !== "rocket") out.push(n);
    for (const c of n.children) flatten(c, out);
    return out;
  }
  let parts = $derived(flatten(app.design?.tree));

  function partsFor(key: string) {
    const want = PART_INPUTS[key];
    if (want === "any") return parts;
    if (want === "mount") return parts.filter((p) => p.motorMount);
    return parts.filter((p) => (want as string[]).includes(p.type));
  }

  const NUMBER = /^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/;

  function args(): Record<string, unknown> {
    const out: Record<string, unknown> = { designId: app.design?.designId };
    for (const i of [...inputs.main, ...inputs.conditions, ...inputs.more]) {
      let raw = (values[i.key] ?? "").trim();
      if (raw === "") continue;
      // A decimal comma (6,5) is read as a point; 1,000 could be either, so it is left for the engine to question.
      if (i.kind === "number" || i.kind === "qty") raw = raw.replace(/^(-?\d+),(\d{1,2}|\d{4,})(?=$|\s)/, "$1.$2");
      if (i.kind === "rows") {
        const props = i.schema.items?.properties ?? {};
        const list = filledRows(raw).map((row) => {
          const o: Record<string, unknown> = {};
          for (const [k, v] of Object.entries(row)) {
            const t = String(v ?? "").trim();
            if (!t) continue;
            const ty = props[k]?.type;
            if (ty === "boolean") o[k] = t === "true";
            else if ((ty === "number" || ty === "integer" || Array.isArray(ty)) && NUMBER.test(t)) o[k] = Number(t);
            else o[k] = t;
          }
          return o;
        });
        if (list.length) out[i.key] = list;
        continue;
      }
      if (i.kind === "bool") out[i.key] = raw === "true";
      else if (i.kind === "number") out[i.key] = NUMBER.test(raw) ? Number(raw) : raw;
      else if (i.kind === "qty") out[i.key] = withUnit(i, raw);
      else if (i.kind === "list") {
        const numeric = i.schema.items?.type === "number" || i.schema.items?.type === "integer";
        out[i.key] = raw.split(/[,;]+/).map((x) => x.trim()).filter(Boolean)
          .map((x) => (quantities(i) ? withUnit(i, x) : numeric && NUMBER.test(x) ? Number(x) : x));
      } else out[i.key] = raw;
    }
    if (!info?.inputSchema.properties?.designId) delete out.designId;
    return out;
  }

  async function go() {
    if (missing.length) return;
    const tool = entry.tool;
    const a = args();
    running = true;
    delete analysis.failures[tool];
    const t0 = performance.now();
    const r = await run(() => callFull(tool, a), entry.label, (l) => (analysis.failures[tool] = l));
    running = false;
    if (!r) return;
    if (!info?.annotations?.readOnlyHint) await refresh(); // it may have changed the design (apply)
    analysis.results[tool] = { ...r, ms: performance.now() - t0, revision: app.revision, designId: app.design?.designId ?? "" };
  }

  async function browse(key: string) {
    try {
      const r = await call<{ path: string | null }>("app_choose_file", { mode: "open", types: entry.files?.[key] ?? "csv" });
      if (r.path) values[key] = r.path;
    } catch {
      /* no file dialog here: the path can be typed */
    }
  }

  /** The result without its top-level problem list when those already show as notes above it. */
  function noted(d: any) {
    if (!d || typeof d !== "object" || Array.isArray(d) || !outcome?.issues?.length) return d;
    const { PROBLEMS: _p, problems: _q, ...rest } = d;
    return rest;
  }

  function choose(a: Analysis) {
    ensure(a);
    analysis.selected = a.tool;
  }

  // The list's filter: the analyses whose name, summary, guide or usual words (FEA, chute, wind) match.
  let filter = $state("");
  const FOUND = new Map<Entry, { a: Analysis; group: string }>();
  const SEARCHABLE: Entry[] = CATALOG.flatMap((g) =>
    g.items.map((a) => {
      const e: Entry = { kind: "analysis", title: a.label, sub: a.blurb, keys: ANALYSIS_KEYS[a.tool], text: analysisText(a, g.title), run: () => choose(a) };
      FOUND.set(e, { a, group: g.title });
      return e;
    }),
  );
  let filtered = $derived(filter.trim() ? search(SEARCHABLE, filter, 80).map((e) => FOUND.get(e)!) : null);

  // ------------------------------------------------------------------ right-click menus
  const canRun = () => !running && !!info && missing.length === 0;
  function clearInputs(a: Analysis) {
    analysis.values[a.tool] = { ...(a.defaults?.(fileName()) ?? {}) };
    delete analysis.failures[a.tool];
  }
  function clearResult(tool: string) {
    delete analysis.results[tool];
    delete analysis.failures[tool];
  }
  /** Opens an analysis and runs it as soon as its form is ready (when nothing it needs is missing). */
  function openAndRun(a: Analysis) {
    choose(a);
    let tries = 0;
    const wait = () => {
      if (entry.tool !== a.tool) return;
      if (info && !running) {
        if (missing.length) toast(`${a.label} needs: ${missing.join(", ")}`, "error");
        else go();
      } else if (tries++ < 40) setTimeout(wait, 100);
    };
    setTimeout(wait, 50);
  }
  function itemItems(a: Analysis): MenuItem[] {
    const has = !!analysis.results[a.tool];
    return [
      { header: a.label },
      { label: "Open", run: () => choose(a) },
      { label: "Run with these inputs", run: () => openAndRun(a) },
      { label: "Clear its inputs", run: () => clearInputs(a) },
      { label: "Clear its result", off: !has && "No result", run: () => clearResult(a.tool) },
      "-",
      { label: "Fold every group", run: () => CATALOG.forEach((g) => setOpen("analysis.group." + g.title, false)) },
      { label: "Unfold every group", run: () => CATALOG.forEach((g) => setOpen("analysis.group." + g.title, true)) },
      { label: "Which analysis do I need? (Guide)", run: () => (ui.guide = "analyses") },
    ];
  }
  function formItems(): MenuItem[] {
    return [
      { header: entry.label },
      { label: "Run", off: !canRun() && (missing.length ? "Needs: " + missing.join(", ") : "Not ready"), run: go },
      { label: "Clear the inputs", run: () => clearInputs(entry) },
      { label: "Unfold every option", run: () => ["analysis.more", "analysis.conditions"].forEach((id) => setOpen(id, true)) },
      "-",
      { label: "Which analysis do I need? (Guide)", run: () => (ui.guide = "analyses") },
      { label: "Start here", run: () => (analysis.selected = "start") },
    ];
  }
  function resultItems(): MenuItem[] {
    return [
      { header: "Result" },
      { label: "Copy the result", off: !outcome && "Not run yet", run: () => copyText(JSON.stringify(outcome!.data, null, 2)).then((ok) => ok && toast("Copied the result")) },
      { label: stale ? "Run again (the design changed)" : "Run again", off: !canRun() && "Not ready", run: go },
      { label: "Clear the result", off: !outcome && !failure && "Nothing to clear", run: () => clearResult(entry.tool) },
      "-",
      { label: "Design review report", run: () => choose(CATALOG.flatMap((g) => g.items).find((x) => x.tool === "generate_report")!) },
    ];
  }
  pageMenu(() => [
    { label: "Start here", run: () => (analysis.selected = "start") },
    !starting ? { label: `Run ${entry.label}`, off: !canRun() && "Not ready", run: go } : null,
    { label: "Find an analysis", run: () => (document.querySelector(".filter input") as HTMLElement | null)?.focus() },
    { label: "Which analysis do I need? (Guide)", run: () => (ui.guide = "analyses") },
  ]);

  function filterKey(e: KeyboardEvent) {
    if (e.key === "Escape") filter = "";
    else if (e.key === "Enter" && filtered?.length) choose(filtered[0].a);
  }
</script>

{#snippet about(i: Input, h: FieldHelp)}
  {@const ex = example(h, imperial)}
  {@const empty = whenEmpty(i)}
  {@const unit = bareUnit(h, imperial)}
  <div class="about">
    <p class="th">{label(i.key)}{#if i.required}<span class="muted"> · required</span>{:else if i.oneOf}<span class="muted"> · one of {eitherList} is needed</span>{/if}</p>
    {#if h.help || described(i)}<p>{h.help ?? described(i)}</p>{/if}
    {#if ex}<p><span class="k">Example</span>{ex}</p>{/if}
    {#if empty && !i.required}<p><span class="k">If empty</span>{empty}</p>{/if}
    {#if quantities(i)}
      <p class="muted">{unit ? `A number without a unit is read as ${unit}.` : "Type the unit after the number. A number without one is in SI units: m, kg, s, N, Pa, m/s."}</p>
    {/if}
  </div>
{/snippet}

{#snippet lab(i: Input, isLabel: boolean)}
  {@const h = fieldHelp(entry.tool, i.key)}
  <span class="lab">
    {#if isLabel}<label for={"a-" + i.key}>{label(i.key)}</label>{:else}{label(i.key)}{/if}{#if i.required || i.oneOf}<b class="req" title={i.required ? "Required" : `One of ${eitherList} is needed`}>*</b>{/if}<Tip label="About {label(i.key)}">{@render about(i, h)}</Tip>
  </span>
{/snippet}

{#snippet field(i: Input)}
  {#if i.kind === "rows"}
    <div class="rowsfield" class:bad={failure?.field === i.key}>
      {@render lab(i, false)}
      <RowsEditor items={i.schema.items} bind:value={values[i.key]} {parts} />
    </div>
  {:else if i.kind === "long"}
    <div class="rowsfield" class:bad={failure?.field === i.key}>
      {@render lab(i, true)}
      <textarea id={"a-" + i.key} class="long" rows="4" bind:value={values[i.key]} placeholder={firstSentence(described(i))}></textarea>
    </div>
  {:else}
  <div class="row" class:bad={failure?.field === i.key} title={failure?.field === i.key ? failure.text : undefined}>
    {@render lab(i, true)}
    {#if i.kind === "sim"}
      <select id={"a-" + i.key} class="select" bind:value={values[i.key]}>
        <option value="">Active configuration</option>
        {#each sims as s}<option value={s}>{s}</option>{/each}
      </select>
    {:else if i.kind === "config"}
      <select id={"a-" + i.key} class="select" bind:value={values[i.key]}>
        <option value="">Active</option>
        {#each app.design?.configurations ?? [] as c (c.id)}<option value={c.id}>{c.name}</option>{/each}
      </select>
    {:else if i.kind === "part"}
      <select id={"a-" + i.key} class="select" bind:value={values[i.key]}>
        <option value="">{i.required ? "Choose…" : "Default"}</option>
        {#each partsFor(i.key) as p (p.id)}<option value={p.id}>{p.name}</option>{/each}
      </select>
    {:else if i.kind === "pin"}
      <select id={"a-" + i.key} class="select" bind:value={values[i.key]}>
        <option value="">{i.required ? "Choose…" : "Team default"}</option>
        {#each pins as p}<option value={p}>{p}</option>{/each}
      </select>
    {:else if i.kind === "design"}
      <select id={"a-" + i.key} class="select" bind:value={values[i.key]}>
        <option value="">None</option>
        {#each designs as d (d.designId)}<option value={d.designId}>{d.name}</option>{/each}
      </select>
    {:else if i.kind === "file"}
      <span class="file">
        <input id={"a-" + i.key} class="input" bind:value={values[i.key]} placeholder="path of the file" />
        <button class="btn" type="button" onclick={() => browse(i.key)}>Browse…</button>
      </span>
    {:else if i.kind === "enum"}
      <select id={"a-" + i.key} class="select" bind:value={values[i.key]}>
        {#if !i.required}<option value="">Default</option>{/if}
        {#each i.schema.enum as e}<option value={e}>{e}</option>{/each}
      </select>
    {:else if i.kind === "bool"}
      <select id={"a-" + i.key} class="select" bind:value={values[i.key]}>
        <option value="">Default</option><option value="true">Yes</option><option value="false">No</option>
      </select>
    {:else}
      <input id={"a-" + i.key} class="input" bind:value={values[i.key]} placeholder={placeholder(i)}
        onkeydown={(e) => e.key === "Enter" && go()} />
    {/if}
  </div>
  {/if}
{/snippet}

{#snippet guideBody(g: Guide)}
  <div class="guide">
    <h4>Before you run</h4>
    <ul>{#each g.needs as n}<li>{n}</li>{/each}</ul>
    <h4>You get</h4>
    <ul>{#each g.gives as n}<li>{n}</li>{/each}</ul>
    {#if g.how}<h4>How it works</h4><p>{g.how}</p>{/if}
    {#if g.not}<h4>What it is not</h4><p>{g.not}</p>{/if}
    {#if g.example}<h4>Example</h4><p>{g.example}</p>{/if}
  </div>
{/snippet}

{#snippet fold(id: string, title: string, list: Input[])}
  {#if list.length}
    <section class="fold">
      <button class="fh" onclick={() => toggle(id, false)} aria-expanded={isOpen(id, false)}>
        <span class="chev" class:shut={!isOpen(id, false)}><ChevronDown size={14} /></span>{title}
      </button>
      {#if isOpen(id, false)}
        <div class="fields">{#each list as i (i.key)}{@render field(i)}{/each}</div>
      {/if}
    </section>
  {/if}
{/snippet}

<div class="page">
  <div class="left" class:shut={!isOpen("analysis.list")}>
    <Panel id="analysis.list" icon={ClipboardList} title="Analyses" side="left">
      <div class="filter">
        <Search size={14} />
        <input bind:value={filter} onkeydown={filterKey} placeholder="Filter, e.g. FEA, chute, wind" aria-label="Filter analyses" spellcheck="false" />
        {#if filter}<button class="clear" onclick={() => (filter = "")} aria-label="Clear the filter"><X size={13} /></button>{/if}
      </div>
      <nav class="cat">
        {#if filtered}
          {#each filtered as x (x.a.tool)}
            <button class="item flat" class:on={x.a.tool === analysis.selected} onclick={() => choose(x.a)} title={x.a.blurb} use:context={() => itemItems(x.a)}>
              <span class="nm">{x.a.label}<span class="grp">{x.group}</span></span>
              {#if analysis.results[x.a.tool]}<span class="ran" title="Has a result"></span>{/if}
            </button>
          {:else}
            <p class="none">No analysis matches “{filter}”. The search ({MAC ? "⌘K" : "Ctrl+K"}) also finds pages, parts and settings.</p>
          {/each}
        {:else}
        <button class="item start" class:on={starting} onclick={() => (analysis.selected = "start")}>
          <span class="si"><Compass size={15} /> Start here</span>
        </button>
        {#each CATALOG as g (g.title)}
          {@const gid = "analysis.group." + g.title}
          {@const GI = GROUP_ICONS[g.title] ?? ClipboardList}
          <button class="gh" onclick={() => toggle(gid)} aria-expanded={isOpen(gid)}>
            <span class="gi"><GI size={14} /></span><span class="gtt">{g.title}</span>
            <span class="chev" class:shut={!isOpen(gid)}><ChevronDown size={14} /></span>
          </button>
          {#if isOpen(gid)}
            {#each g.items as a (a.tool)}
              <button class="item" class:on={a.tool === analysis.selected} onclick={() => choose(a)} use:context={() => itemItems(a)}>
                {a.label}
                {#if analysis.results[a.tool]}<span class="ran" title="Has a result"></span>{/if}
              </button>
            {/each}
          {/if}
        {/each}
        {/if}
      </nav>
    </Panel>
  </div>

  <div class="main">
    {#if starting}
    <Panel id="analysis.start" icon={Compass} title="Start here: which analysis answers your question?" fill>
      <StartHere choose={(tool) => { const a = CATALOG.flatMap((g) => g.items).find((x) => x.tool === tool); if (a) choose(a); }} />
    </Panel>
    {:else}
    <Panel id="analysis.inputs" icon={SlidersHorizontal} title={entry.label}>
      {#snippet tools()}
        {#if missing.length}<span class="meta">Needs: {missing.join(", ")}</span>{/if}
        <button class="btn primary run" disabled={running || !info || missing.length > 0} onclick={go}><Play size={14} /> {running ? "Running…" : "Run"}</button>
      {/snippet}
      <div class="form" use:context={formItems}>
        <!-- Built afresh for each analysis, so a list left empty shows its first choice (Default) rather than nothing. -->
        {#key entry.tool}
        {#if info}
          <p class="desc">
            {entry.blurb ?? firstSentence(info.description)}
            {#if guide}<Tip wide size={14} label="What {entry.label} needs and gives">{@render guideBody(guide)}</Tip>{/if}
          </p>
          {#if noMotor}
            <div class="need">
              <b>Needs a motor.</b> Configuration “{flown?.name}” has none, so there is no flight to analyse.
              <button class="link" onclick={() => (app.page = "motors")}>Choose a motor</button>
            </div>
          {/if}
          <div class="fields">{#each inputs.main as i (i.key)}{@render field(i)}{/each}</div>
          {@render fold("analysis.more", "More options", inputs.more)}
          {@render fold("analysis.conditions", "Launch conditions for this run", inputs.conditions)}
          {#if [...inputs.main, ...inputs.more].some((i) => i.required || i.oneOf)}
            <p class="legend"><b class="req">*</b> needed{#if entry.oneOf}{" "}(of {eitherList}, one is enough){/if} · ⓘ what to enter</p>
          {:else}
            <p class="legend">Everything is optional: empty inputs use the design and the team standards · ⓘ what to enter</p>
          {/if}
        {:else}
          <p class="muted">Loading…</p>
        {/if}
        {/key}
      </div>
    </Panel>

    <Panel id="analysis.results" icon={ListChecks} title="Result" fill>
      {#snippet tools()}
        {#if outcome}<span class="meta">{stale ? "Design changed since this ran. " : ""}{(outcome.ms / 1000).toFixed(1)} s</span>{/if}
      {/snippet}
      <div class="out" class:stale use:context={resultItems}>
        {#if running}<RocketLoader text={entry.label} />{/if}
        {#if failure && !running}
          <div class="issue k-{failure.kind} error">
            <span class="tag">{KINDS[failure.kind].label}</span>
            <div>
              {failure.text}
              {#if failure.field}<div class="sub">Check {label(failure.field)}, marked in the form above.</div>{/if}
              {#if failure.causes?.length}
                <div class="why">
                  <span class="wh">What could be causing it</span>
                  <ul>{#each failure.causes as c}<li>{c}</li>{/each}</ul>
                </div>
              {/if}
              {#if failure.fix}<div class="sub"><b>Try:</b> {failure.fix}</div>{/if}
              {#if failure.bug}<div class="sub">A bug in RocketMaxxing. <button class="link" onclick={openIssues}>Report it</button></div>{/if}
            </div>
          </div>
        {/if}
        {#if outcome && !failure}
          {#each outcome.issues ?? [] as i, j (j)}
            <div class="issue k-{i.kind} {i.severity}"><span class="tag">{KINDS[i.kind].label}</span><div>{plain(i.text)}</div></div>
          {/each}
        {/if}
        {#if outcome}
          {#if entry.plot}<ResultPlot data={outcome.data} spec={entry.plot} name={entry.label} />{/if}
          {#each outcome.images as img}<img src={img.src} alt="Plot" />{/each}
          <Result value={noted(outcome.data)} />
        {:else}
          {#if !running && !failure}<p class="muted">Not run yet.</p>{/if}
        {/if}
      </div>
    </Panel>
    {/if}
  </div>
</div>

<style>
  .page {
    flex: 1;
    min-height: 0;
    display: flex;
    gap: var(--gap);
    padding: var(--gap);
  }
  .left {
    width: 270px;
    flex: none;
    min-height: 0;
  }
  .left.shut {
    width: auto;
  }
  .main {
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: var(--gap);
  }
  .cat {
    display: flex;
    flex-direction: column;
    padding: 3px 0;
  }
  .gh {
    display: flex;
    align-items: center;
    gap: 10px;
    border: 0;
    background: transparent;
    padding: 10px 12px 6px 14px;
    font-weight: 600;
    text-align: left;
    color: var(--ink);
  }
  .gi {
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    border-radius: 6px;
    background: var(--surface-2);
    color: var(--ink-2);
  }
  .gtt {
    flex: 1;
  }
  .chev {
    display: flex;
    color: var(--ink-3);
    transition: transform 0.1s;
  }
  .chev.shut {
    transform: rotate(-90deg);
  }
  .item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    border: 0;
    background: transparent;
    padding: 7px 12px 7px 46px;
    text-align: left;
    color: var(--ink-2);
  }
  .item:hover {
    background: var(--surface-2);
    color: var(--ink);
  }
  .item.on {
    background: var(--select-soft);
    color: var(--ink);
  }
  .filter {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 12px 12px 6px;
    padding: 0 9px;
    height: var(--control);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface);
    color: var(--ink-3);
  }
  .filter:focus-within {
    border-color: var(--select);
  }
  .filter input {
    flex: 1;
    min-width: 0;
    border: 0;
    outline: none;
    background: transparent;
    color: var(--ink);
    font: inherit;
  }
  .clear {
    display: grid;
    place-items: center;
    border: 0;
    background: transparent;
    color: var(--ink-3);
    padding: 2px;
  }
  .item.start {
    padding-left: 14px;
    margin: 4px 0;
    font-weight: 600;
    color: var(--ink);
  }
  .si {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .item.flat {
    padding-left: 14px;
  }
  .nm {
    display: flex;
    flex-direction: column;
  }
  .grp {
    font-size: 11px;
    color: var(--ink-3);
  }
  .none {
    margin: 6px 12px;
    font-size: 12px;
    color: var(--ink-3);
  }
  .ran {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--ink-3);
  }
  .run {
    height: 30px;
  }
  .form {
    padding: 14px 16px 16px;
    max-height: 46vh;
    overflow: auto;
  }
  .desc {
    margin: 0 0 14px;
    color: var(--ink-2);
  }
  .fields {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
    gap: 14px 20px;
    align-items: start;
  }
  /* Label above its box: long labels no longer squeeze the inputs. */
  .row {
    display: grid;
    grid-template-columns: 1fr;
    align-items: center;
    gap: 5px;
  }
  .lab {
    color: var(--ink-2);
  }
  .req {
    color: var(--bad);
    margin-left: 2px;
  }
  .lab label {
    cursor: default;
  }
  .need {
    margin: 0 0 8px;
    padding: 6px 10px;
    border-left: 3px solid var(--warn);
    border-radius: var(--radius);
    background: var(--warn-soft, var(--surface-2));
  }
  .legend {
    margin: 8px 0 0;
    font-size: 11.5px;
    color: var(--ink-3);
  }
  .about p,
  .guide p {
    margin: 0 0 5px;
  }
  .about p:last-child,
  .guide p:last-child {
    margin-bottom: 0;
  }
  .about .th {
    font-weight: 600;
  }
  .about .k {
    display: inline-block;
    min-width: 62px;
    margin-right: 6px;
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.03em;
    color: var(--ink-3);
  }
  .guide h4 {
    margin: 8px 0 3px;
    font-size: 11px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--ink-3);
  }
  .guide h4:first-child {
    margin-top: 0;
  }
  .guide ul {
    margin: 0 0 4px;
    padding-left: 16px;
  }
  .guide li {
    margin: 0 0 3px;
  }
  .row .input,
  .row .select {
    width: 100%;
  }
  .row.bad .input,
  .row.bad .select,
  .rowsfield.bad textarea {
    border-color: var(--bad);
    box-shadow: 0 0 0 1px var(--bad);
  }
  .row.bad .lab,
  .rowsfield.bad .lab {
    color: var(--bad);
    font-weight: 600;
  }
  .issue {
    display: flex;
    gap: 8px;
    align-items: baseline;
    margin: 0 0 6px;
    padding: 6px 10px;
    border-radius: var(--radius);
    border-left: 3px solid var(--kind);
    background: var(--surface-2);
    font-size: 12.5px;
    --kind: var(--ink-3);
  }
  .issue.k-input {
    --kind: var(--select);
  }
  .issue.k-physics {
    --kind: var(--bad);
  }
  .issue.k-physics.warning,
  .issue.k-output {
    --kind: var(--warn);
  }
  .issue.k-system {
    --kind: var(--ink-2);
  }
  .issue .tag {
    flex: none;
    font-size: 10.5px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--kind);
  }
  .issue .why {
    margin-top: 6px;
    font-size: 12.5px;
    color: var(--ink-2);
  }
  .issue .why .wh {
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--ink-3);
  }
  .issue .why ul {
    margin: 2px 0 2px;
    padding-left: 18px;
  }
  .issue .sub {
    margin-top: 2px;
    color: var(--ink-3);
  }
  .link {
    border: 0;
    background: transparent;
    color: var(--select);
    padding: 0;
    text-decoration: underline;
  }
  .rowsfield {
    grid-column: 1 / -1;
    display: flex;
    flex-direction: column;
    gap: 3px;
    padding: 2px 0 4px;
  }
  .long {
    resize: vertical;
    font: inherit;
    font-size: 12px;
    padding: 4px 6px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface);
    color: var(--ink);
  }
  .long:focus {
    outline: none;
    border-color: var(--select);
  }
  .file {
    display: flex;
    gap: 4px;
    min-width: 0;
  }
  .file .input {
    flex: 1;
    min-width: 0;
  }
  .fold {
    margin-top: 14px;
    border-top: 1px solid var(--line);
  }
  .fold .fields {
    padding-bottom: 6px;
  }
  .fh {
    display: flex;
    align-items: center;
    gap: 6px;
    border: 0;
    background: transparent;
    padding: 10px 0;
    font-weight: 600;
  }
  .meta {
    font-size: 12px;
    color: var(--ink-3);
    white-space: nowrap;
  }
  .out {
    padding: 14px 16px 16px;
  }
  .out.stale {
    opacity: 0.75;
  }
  img {
    max-width: 100%;
    display: block;
    margin: 4px 0 10px;
    border: 1px solid var(--line);
    background: #fff;
  }
</style>
