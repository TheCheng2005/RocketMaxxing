<script lang="ts">
  import { X, Copy, Download, Bug, Trash2 } from "@lucide/svelte";
  import { app } from "../state.svelte";
  import { label } from "../analysis/text";
  import type { IssueKind } from "../api";
  import { issues, KINDS, ORDER, reportText, type Logged } from "./issues.svelte";

  // Every problem in one place, by whose it is: the request (input), the flight (physics), a result to treat with
  // care (output), the computer or RocketMaxxing (system). Bugs in RocketMaxxing come with a report to send.

  const REPO_ISSUES = "https://github.com/TheCheng2005/RocketMaxxing/issues/new";

  let only = $state<IssueKind | null>(null);
  let copied = $state(0);

  let now = $derived(app.design ? app.flightIssues : []);
  let shownNow = $derived(now.filter((i) => !only || i.kind === only));
  let shownLog = $derived(issues.log.filter((l) => !only || l.kind === only));
  let counts = $derived.by(() => {
    const c: Record<IssueKind, number> = { input: 0, physics: 0, output: 0, system: 0 };
    for (const i of [...now, ...issues.log]) c[i.kind]++;
    return c;
  });

  function time(d: Date) {
    return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
  }

  async function copy(l: Logged) {
    try {
      await navigator.clipboard.writeText(reportText(l, app.session));
      copied = l.n;
      setTimeout(() => (copied = 0), 2000);
    } catch {
      /* no clipboard in this window: Save instead */
    }
  }

  function save(l: Logged) {
    const blob = new Blob([reportText(l, app.session)], { type: "text/plain" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `rocketmaxxing-error-${l.id ?? l.n}.txt`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }

  function issueLink(l: Logged): string {
    const body = reportText(l, app.session);
    const title = `Error ${l.id ? l.id + " " : ""}in ${l.tool ?? l.where}: ${l.text.slice(0, 80)}`;
    return `${REPO_ISSUES}?title=${encodeURIComponent(title)}&body=${encodeURIComponent("```\n" + body.slice(0, 5000) + "\n```")}`;
  }
</script>

<aside class="drawer" aria-label="Problems">
  <header>
    <b>Problems</b>
    <span class="spacer"></span>
    <button class="tool" title="Clear the list" disabled={!issues.log.length} onclick={() => (issues.log = [])}><Trash2 size={15} /></button>
    <button class="tool" title="Close" onclick={() => (issues.open = false)}><X size={16} /></button>
  </header>

  <div class="kinds" role="group" aria-label="Show">
    <button class="chip" class:on={!only} onclick={() => (only = null)}>All</button>
    {#each ORDER as k (k)}
      <button class="chip k-{k}" class:on={only === k} title={KINDS[k].about} onclick={() => (only = only === k ? null : k)}>
        {KINDS[k].label}{#if counts[k]}<span class="n">{counts[k]}</span>{/if}
      </button>
    {/each}
  </div>
  {#if only}<p class="about">{KINDS[only].about}</p>{/if}

  <div class="list">
    {#if app.design}
      <h3>This design now <span class="muted">quick flight of the active configuration</span></h3>
      {#each shownNow as i, j (j)}
        <div class="item k-{i.kind} {i.severity}">
          <div class="head"><span class="tag">{KINDS[i.kind].label}</span></div>
          <div class="text">{i.text}</div>
        </div>
      {:else}
        <p class="muted none">{app.flying ? "Flying…" : only ? "Nothing of this kind." : "Nothing to watch in the flight."}</p>
      {/each}
    {/if}

    <h3>Recent</h3>
    {#each shownLog as l (l.n)}
      <div class="item k-{l.kind} {l.severity}">
        <div class="head">
          <span class="tag">{KINDS[l.kind].label}</span>
          <span class="where">{l.where + (l.tool ? " · " + l.tool : "")}</span>
          <span class="time">{time(l.at)}</span>
        </div>
        <div class="text">{l.text}</div>
        {#if l.severity === "error" && (l.causes.length || l.fix)}
          <div class="why">
            {#if l.causes.length}
              <div class="wh">What could be causing it</div>
              <ul>{#each l.causes as c}<li>{c}</li>{/each}</ul>
            {/if}
            {#if l.fix}<div class="fix"><b>Try:</b> {l.fix}</div>{/if}
          </div>
        {/if}
        {#if l.field}<div class="sub">About: {label(l.field)}</div>{/if}
        {#if l.bug}
          <div class="bug">
            <Bug size={13} />
            <span>A bug in RocketMaxxing{#if l.id}, error ID <code>{l.id}</code>{/if}. Sending the report helps fix it; read it first, it
              lists what was asked.</span>
          </div>
          <div class="acts">
            <button class="btn" onclick={() => copy(l)}><Copy size={13} /> {copied === l.n ? "Copied" : "Copy report"}</button>
            <button class="btn" onclick={() => save(l)}><Download size={13} /> Save report</button>
            <a class="btn" href={issueLink(l)} target="_blank" rel="noopener noreferrer">Report on GitHub</a>
          </div>
        {/if}
      </div>
    {:else}
      <p class="muted none">{only ? "Nothing of this kind." : "No errors since the app started."}</p>
    {/each}
  </div>
</aside>

<style>
  .drawer {
    width: 380px;
    flex: none;
    display: flex;
    flex-direction: column;
    border-left: 1px solid var(--line);
    background: var(--surface);
    min-height: 0;
  }
  header {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 6px 6px 6px 12px;
    border-bottom: 1px solid var(--line);
  }
  .spacer {
    flex: 1;
  }
  .tool {
    display: grid;
    place-items: center;
    width: 28px;
    height: 28px;
    border: 0;
    border-radius: 4px;
    background: transparent;
    color: var(--ink-2);
  }
  .tool:hover:not(:disabled) {
    background: var(--surface-3);
  }
  .kinds {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    padding: 8px 10px 4px;
  }
  .chip {
    display: flex;
    align-items: center;
    gap: 5px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: var(--surface);
    padding: 2px 9px;
    font-size: 12px;
    color: var(--ink-2);
  }
  .chip.on {
    border-color: var(--select);
    background: var(--select-soft);
    color: var(--ink);
  }
  .n {
    font-size: 11px;
    color: var(--ink-3);
  }
  .why {
    margin-top: 6px;
    font-size: 12.5px;
    color: var(--ink-2);
  }
  .why .wh {
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--ink-3);
  }
  .why ul {
    margin: 2px 0 4px;
    padding-left: 18px;
  }
  .why .fix {
    color: var(--ink);
  }
  .about {
    margin: 2px 12px 4px;
    font-size: 12px;
    color: var(--ink-3);
  }
  .list {
    flex: 1;
    overflow: auto;
    padding: 4px 10px 12px;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  h3 {
    margin: 8px 0 0;
    font-size: 12px;
    font-weight: 600;
    color: var(--ink-2);
  }
  h3 .muted {
    font-weight: 400;
    margin-left: 4px;
  }
  .none {
    margin: 0;
    font-size: 12.5px;
  }
  .item {
    flex: none;
    border: 1px solid var(--line);
    border-left: 3px solid var(--kind);
    border-radius: var(--radius);
    background: var(--surface-2);
    padding: 6px 8px;
    font-size: 12.5px;
    --kind: var(--ink-3);
  }
  .item.k-input {
    --kind: var(--select);
  }
  .item.k-physics {
    --kind: var(--bad);
  }
  .item.k-output {
    --kind: var(--warn);
  }
  .item.k-system {
    --kind: var(--ink-2);
  }
  .item.k-physics.warning {
    --kind: var(--warn);
  }
  .head {
    display: flex;
    align-items: baseline;
    gap: 6px;
    margin-bottom: 2px;
  }
  .tag {
    font-size: 10.5px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--kind);
  }
  .where {
    flex: 1;
    min-width: 0;
    font-size: 11.5px;
    color: var(--ink-3);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .time {
    font-size: 11px;
    color: var(--ink-3);
  }
  .text {
    line-height: 1.4;
    word-break: break-word;
  }
  .sub {
    margin-top: 2px;
    font-size: 11.5px;
    color: var(--ink-3);
  }
  .bug {
    display: flex;
    gap: 6px;
    align-items: flex-start;
    margin-top: 6px;
    font-size: 12px;
    color: var(--ink-2);
  }
  .bug :global(svg) {
    flex: none;
    margin-top: 2px;
  }
  code {
    background: var(--surface-3);
    padding: 0 3px;
    border-radius: 3px;
  }
  .acts {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    margin-top: 6px;
  }
  .acts .btn {
    font-size: 12px;
    height: 24px;
    text-decoration: none;
  }
</style>
