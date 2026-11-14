<script lang="ts">
  import { app, autoCheck, setAutoCheck, checkRules, toast, type Checks } from "../state.svelte";
  import { context, copyText, type MenuItem } from "../menu/menu.svelte";
  import { analysis } from "../analysis/store.svelte";
  import { status } from "../analysis/text";
  import { explain, plain } from "../issues/explain";

  // `data` shows one finished check (the Analysis page's result); without it, the panel follows the design's own check.
  let { tall = false, data = null }: { tall?: boolean; data?: Omit<Checks, "designId"> | null } = $props();
  // The competition rule check, re-run after edits: failures and warnings first, passes on request.
  type Level = "fail" | "warn" | "info" | "pass";
  const LEVELS: { s: Level; label: string }[] = [{ s: "fail", label: "Fail" }, { s: "warn", label: "Warn" }, { s: "info", label: "Info" }, { s: "pass", label: "Pass" }];
  let show = $state<Record<Level, boolean>>({ fail: true, warn: true, info: true, pass: false });

  let c = $derived(data ?? (app.checks && app.checks.designId === app.design?.designId ? app.checks : null));
  let live = $derived(!data);
  /** The engine writes comparisons for plain text; the app can show the real signs. */
  const signs = (t: string) => plain(t).replace(/>=/g, "≥").replace(/<=/g, "≤");
  // The engine words its rows for AI assistants too (tool names, standards keys): shown in the app's words.
  let rows = $derived((c?.checks ?? []).map((x) => ({
    ...x, item: signs(x.item), requirement: x.requirement ? signs(x.requirement) : x.requirement, value: x.value ? signs(x.value) : x.value,
    s: (status(x.status) || "info") as Level,
  })));
  let manual = $derived(c?.manualChecks ?? []);
  /** Why there is no check, with what could be behind it and what to do. */
  let failed = $derived(c?.error ? explain({ kind: "input", severity: "error", text: c.error }) : null);
  const order: Record<Level, number> = { fail: 0, warn: 1, info: 2, pass: 3 };
  let shown = $derived(rows.filter((r) => show[r.s]).sort((a, b) => order[a.s] - order[b.s]));
  let counts = $derived.by(() => {
    const n: Record<Level, number> = { fail: 0, warn: 0, info: 0, pass: 0 };
    for (const r of rows) n[r.s]++;
    return n;
  });
  let verdict = $derived(counts.fail ? "" : counts.warn ? "Nothing fails." : rows.length ? "Every check passes." : "");

  // Right-click: check now, copy the results, show or hide passes, change the rule set.
  function items(e: MouseEvent): MenuItem[] {
    const li = (e.target as HTMLElement).closest(".rule") as HTMLElement | null;
    const i = li ? Number(li.dataset.i) : -1;
    const r = i >= 0 ? shown[i] : null;
    const line = (x: (typeof rows)[number]) => `${x.status}  ${x.item}${x.value ? ": " + x.value : ""}${x.requirement ? " (" + x.requirement + ")" : ""}${x.ref ? " [" + x.ref + "]" : ""}`;
    return [
      { header: "Rule check" },
      r ? { label: "Copy this line", run: () => copyText(line(r)).then((ok) => ok && toast("Copied")) } : null,
      { label: "Copy all results", off: !rows.length && "No results yet", run: () => copyText([c?.ruleSet ?? "", ...rows.map(line), ...(manual.length ? ["", "To check by hand:", ...manual.map((m) => "- " + m)] : [])].join("\n")).then((ok) => ok && toast("Copied the rule check")) },
      live ? { label: "Check again", run: () => checkRules() } : null,
      { label: show.pass ? "Hide passed" : "Show passed", off: !counts.pass && "Nothing passed", run: () => (show.pass = !show.pass) },
      { label: "Show everything", run: () => (show = { fail: true, warn: true, info: true, pass: true }) },
      "-",
      live ? { label: "Check after every edit", checked: autoCheck(), run: () => setAutoCheck(!autoCheck()) } : null,
      { label: "Rule set and launch site (Standards)", run: () => (app.page = "standards") },
      { label: "Targets", run: () => (app.page = "targets") },
      live ? { label: "Rule check with other conditions (Analysis)", run: () => ((analysis.selected = "check_requirements"), (app.page = "analysis")) } : null,
    ].filter(Boolean) as MenuItem[];
  }
</script>

<div class="rc" class:stale={live && app.checking} class:tall use:context={items}>
  {#if !c}
    <p class="muted pad">{autoCheck() ? "Waiting for the first check…" : "Click the refresh button to check the rules (automatic checking is off in Settings)."}</p>
  {:else if failed}
    <div class="pad none">
      <div><b>Not checked.</b> {failed.text}</div>
      {#if failed.causes.length}
        <ul class="why">{#each failed.causes as cause}<li>{cause}</li>{/each}</ul>
      {/if}
      <div class="acts">
        {#if /motor/i.test(failed.text)}<button class="btn" onclick={() => (app.page = "motors")}>Choose a motor</button>{/if}
        <button class="btn" onclick={() => checkRules()}>Check again</button>
      </div>
    </div>
  {:else}
    <div class="bar">
      <div class="chips" role="group" aria-label="Show">
        {#each LEVELS as l (l.s)}
          {#if counts[l.s] || l.s === "fail"}
            <button class="chip {l.s}" class:on={show[l.s]} class:none={!counts[l.s]} aria-pressed={show[l.s]} disabled={!counts[l.s]}
              title={counts[l.s] ? `${show[l.s] ? "Hide" : "Show"} the ${counts[l.s]} ${l.label.toLowerCase()} row${counts[l.s] === 1 ? "" : "s"}` : "Nothing fails"}
              onclick={() => (show[l.s] = !show[l.s])}><b>{counts[l.s]}</b> {l.label}</button>
          {/if}
        {/each}
      </div>
      {#if c.ruleSet}<span class="set" title="The rule set these checks come from; change it on the Standards page">{c.ruleSet}</span>{/if}
    </div>
    {#if shown.length}
      <ul class="rules">
        {#each shown as r, i (i)}
          <li class="rule {r.s}" data-i={i}>
            <span class="badge {r.s}">{r.status}</span>
            <div class="what">
              <div class="top"><span class="item">{r.item}</span>{#if r.ref}<span class="ref">{r.ref}</span>{/if}</div>
              {#if r.value}<div class="val">{r.value}</div>{/if}
              {#if r.requirement}<div class="req"><span>Required</span> {r.requirement}</div>{/if}
            </div>
          </li>
        {/each}
      </ul>
    {:else}
      <p class="muted pad">{verdict || "Nothing to show."} {rows.length ? "Use the buttons above to choose which rows to list." : ""}</p>
    {/if}
    {#if manual.length}
      <details class="manual">
        <summary>{manual.length} to check by hand <span class="muted">(what a simulation cannot show)</span></summary>
        <ul>{#each manual as m}<li>{signs(m)}</li>{/each}</ul>
      </details>
    {/if}
  {/if}
</div>

<style>
  .rc {
    max-height: 38vh;
    overflow: auto;
  }
  .rc.tall {
    max-height: none;
  }
  .rc.stale {
    opacity: 0.7;
  }
  .pad {
    margin: 0;
    padding: 12px 14px;
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
    font-size: 12.5px;
  }
  .none .acts {
    display: flex;
    gap: 6px;
  }
  .bar {
    position: sticky;
    top: 0;
    z-index: 1;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 6px 12px;
    padding: 8px 14px;
    background: var(--surface);
    border-bottom: 1px solid var(--line);
  }
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .chip {
    padding: 2px 10px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: transparent;
    color: var(--ink-3);
    font-size: 12px;
    white-space: nowrap;
  }
  .chip b {
    font-variant-numeric: tabular-nums;
  }
  .chip.on {
    background: var(--surface-3);
    color: var(--ink);
    border-color: transparent;
  }
  .chip.on.fail {
    background: var(--bad);
    color: #fff;
  }
  .chip.on.warn {
    background: var(--warn-soft);
    color: var(--warn);
  }
  .chip.on.pass {
    background: var(--good-soft);
    color: var(--good);
  }
  .chip.none,
  .chip.none.on {
    background: transparent;
    border-color: var(--line);
    color: var(--ink-3);
    cursor: default;
  }
  .set {
    font-size: 12px;
    color: var(--ink-3);
  }
  .manual {
    padding: 8px 14px;
    border-top: 1px solid var(--line);
    font-size: 12.5px;
    color: var(--ink-2);
  }
  .manual summary {
    cursor: pointer;
  }
  .manual ul {
    margin: 6px 0 0;
    padding-left: 18px;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .rules {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .rule {
    display: grid;
    grid-template-columns: 46px minmax(0, 1fr);
    gap: 12px;
    align-items: start;
    padding: 8px 14px;
    border-bottom: 1px solid var(--line);
    border-left: 3px solid transparent;
  }
  .rule:last-child {
    border-bottom: 0;
  }
  .rule.fail {
    border-left-color: var(--bad);
    background: var(--bad-soft);
  }
  .rule.warn {
    border-left-color: var(--warn);
  }
  .badge {
    text-align: center;
    padding: 2px 0;
    border-radius: 999px;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.03em;
    background: var(--surface-3);
    color: var(--ink-2);
  }
  .badge.pass {
    background: var(--good-soft);
    color: var(--good);
  }
  .badge.warn {
    background: var(--warn-soft);
    color: var(--warn);
  }
  .badge.fail {
    background: var(--bad);
    color: #fff;
  }
  .top {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    justify-content: space-between;
    gap: 0 12px;
  }
  .item {
    font-weight: 600;
  }
  .ref {
    font-size: 11.5px;
    color: var(--ink-3);
    overflow-wrap: anywhere;
  }
  .val {
    margin-top: 1px;
    font-variant-numeric: tabular-nums;
    color: var(--ink-2);
    overflow-wrap: anywhere;
  }
  .req {
    margin-top: 1px;
    font-size: 12px;
    color: var(--ink-3);
  }
  .req span {
    font-size: 10.5px;
    font-weight: 600;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    margin-right: 4px;
  }
</style>
