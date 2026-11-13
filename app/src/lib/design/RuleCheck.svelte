<script lang="ts">
  import { app, autoCheck, setAutoCheck, checkRules, toast } from "../state.svelte";
  import { context, copyText, type MenuItem } from "../menu/menu.svelte";
  import { analysis } from "../analysis/store.svelte";
  import { status } from "../analysis/text";
  import { explain, plain } from "../issues/explain";

  let { tall = false }: { tall?: boolean } = $props();
  // The competition rule check, re-run after edits: failures and warnings first, passes on request.
  let showPassed = $state(false);

  let c = $derived(app.checks && app.checks.designId === app.design?.designId ? app.checks : null);
  type Level = "fail" | "warn" | "info" | "pass";
  // The engine words its rows for AI assistants too (tool names, standards keys): shown in the app's words.
  let rows = $derived((c?.checks ?? []).map((x) => ({
    ...x, item: plain(x.item), requirement: x.requirement ? plain(x.requirement) : x.requirement, value: x.value ? plain(x.value) : x.value,
    s: (status(x.status) || "info") as Level,
  })));
  let manual = $derived(c?.manualChecks ?? []);
  /** Why there is no check, with what could be behind it and what to do. */
  let failed = $derived(c?.error ? explain({ kind: "input", severity: "error", text: c.error }) : null);
  const order: Record<Level, number> = { fail: 0, warn: 1, info: 2, pass: 3 };
  let shown = $derived(rows.filter((r) => showPassed || r.s !== "pass").sort((a, b) => order[a.s] - order[b.s]));
  let passed = $derived(rows.filter((r) => r.s === "pass").length);

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
      { label: "Check again", run: () => checkRules() },
      { label: showPassed ? "Hide passed" : "Show passed", run: () => (showPassed = !showPassed) },
      "-",
      { label: "Check after every edit", checked: autoCheck(), run: () => setAutoCheck(!autoCheck()) },
      { label: "Rule set and launch site (Standards)", run: () => (app.page = "standards") },
      { label: "Targets", run: () => (app.page = "targets") },
      { label: "Rule check in full (Analysis)", run: () => ((analysis.selected = "check_requirements"), (app.page = "analysis")) },
    ].filter(Boolean) as MenuItem[];
  }
</script>

<div class="rc" class:stale={app.checking} class:tall use:context={items}>
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
    <ul class="rules">
      {#each shown as r, i (i)}
        <li class="rule {r.s}" data-i={i}>
          <span class="badge {r.s}">{r.status}</span>
          <div class="what">
            <div class="top"><span class="item">{r.item}</span>{#if r.value}<span class="val">{r.value}</span>{/if}</div>
            {#if r.requirement}<div class="req">{r.requirement}</div>{/if}
            {#if r.ref}<div class="ref">{r.ref}</div>{/if}
          </div>
        </li>
      {/each}
    </ul>
    {#if manual.length}
      <details class="manual">
        <summary>To check by hand ({manual.length}): what the rules ask that a simulation cannot show</summary>
        <ul>{#each manual as m}<li>{plain(m)}</li>{/each}</ul>
      </details>
    {/if}
    <div class="foot">
      <span class="muted">{c.ruleSet}</span>
      <button class="link" onclick={() => (showPassed = !showPassed)}>{showPassed ? "Hide passed" : `Show passed (${passed})`}</button>
    </div>
  {/if}
</div>

<style>
  .rc {
    max-height: 24vh;
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
    padding: 6px 0;
  }
  .rule {
    display: flex;
    gap: 12px;
    align-items: flex-start;
    padding: 10px 14px;
    border-bottom: 1px solid var(--line);
  }
  .rule:last-child {
    border-bottom: 0;
  }
  .rule.fail {
    background: var(--bad-soft);
  }
  .badge {
    flex: none;
    min-width: 46px;
    text-align: center;
    padding: 2px 6px;
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
  .what {
    flex: 1;
    min-width: 0;
  }
  .top {
    display: flex;
    flex-wrap: wrap;
    justify-content: space-between;
    gap: 2px 12px;
  }
  .item {
    font-weight: 600;
  }
  .val {
    font-variant-numeric: tabular-nums;
    color: var(--ink-2);
  }
  .req {
    margin-top: 2px;
    font-size: 12px;
    color: var(--ink-3);
  }
  .ref {
    margin-top: 2px;
    font-size: 11.5px;
    color: var(--ink-3);
    overflow-wrap: anywhere;
  }
  .foot {
    display: flex;
    justify-content: space-between;
    gap: 10px;
    padding: 8px 14px;
    border-top: 1px solid var(--line);
    font-size: 12px;
  }
  .link {
    border: 0;
    background: transparent;
    color: var(--select);
    padding: 0;
    white-space: nowrap;
  }
</style>
