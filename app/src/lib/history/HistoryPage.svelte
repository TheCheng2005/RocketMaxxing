<script lang="ts">
  import { History, Undo2, Clock, ShieldCheck, Save, FolderOpen, Pencil, Bot, User, RotateCcw } from "@lucide/svelte";
  import { untrack } from "svelte";
  import { call } from "../api";
  import { app, act, run, toast, checkRules, checkTargets } from "../state.svelte";
  import Panel from "../Panel.svelte";
  import { context, pageMenu, copyText, type MenuItem } from "../menu/menu.svelte";
  import { plain } from "../issues/explain";

  // What happened to the design and the team's standards: the edits since the last save, the edits this session can
  // still undo (back to any point), and the whole timeline kept in the designs folder, by whom and when.

  interface Event {
    id: string;
    time: string;
    kind: "edit" | "undo" | "redo" | "save" | "open" | "standards";
    by: string;
    design?: string;
    file?: string;
    what: string;
  }
  interface Hist {
    kept: boolean;
    design?: string;
    unsaved?: boolean;
    sinceSave?: Event[];
    undoable?: { what: string; when: string }[];
    events: Event[];
  }

  let h = $state<Hist | null>(null);
  let scope = $state<"design" | "all">("design");
  let kinds = $state<string[]>([]);
  let loading = $state(false);

  async function load() {
    loading = true;
    try {
      h = await call<Hist>("app_history", { ...(scope === "design" && app.design ? { designId: app.design.designId } : {}), limit: 500 });
    } catch {
      h = null;
    } finally {
      loading = false;
    }
  }
  $effect(() => {
    void app.revision;
    void scope;
    void app.design?.designId;
    untrack(load);
  });

  const KIND: Record<string, { label: string; icon: typeof History }> = {
    edit: { label: "Edit", icon: Pencil },
    undo: { label: "Undo", icon: Undo2 },
    redo: { label: "Redo", icon: RotateCcw },
    save: { label: "Saved", icon: Save },
    open: { label: "Opened", icon: FolderOpen },
    standards: { label: "Standards", icon: ShieldCheck },
  };
  let shown = $derived((h?.events ?? []).filter((e) => !kinds.length || kinds.includes(e.kind)));

  /** "14:05", "yesterday 09:12" or "3 Nov 09:12": recent times short, older ones with their day. */
  function when(iso: string): string {
    const d = new Date(iso), now = new Date();
    const t = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    if (d.toDateString() === now.toDateString()) return t;
    const y = new Date(now);
    y.setDate(now.getDate() - 1);
    if (d.toDateString() === y.toDateString()) return `yesterday ${t}`;
    return `${d.toLocaleDateString([], { day: "numeric", month: "short", ...(d.getFullYear() !== now.getFullYear() ? { year: "numeric" } : {}) })} ${t}`;
  }
  /** "23:55:10" from the engine, written the way this computer writes times. */
  function clock(hms: string): string {
    const [hh, mm] = hms.split(":").map(Number);
    const d = new Date();
    d.setHours(hh, mm);
    return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }
  /** Events grouped by day, for the timeline. */
  let days = $derived.by(() => {
    const out: { day: string; events: Event[] }[] = [];
    for (const e of shown) {
      const d = new Date(e.time).toLocaleDateString([], { weekday: "long", day: "numeric", month: "long" });
      if (!out.length || out[out.length - 1].day !== d) out.push({ day: d, events: [] });
      out[out.length - 1].events.push(e);
    }
    return out;
  });

  /** Undoes the newest edits back to (and including) the one at {@code index} of the undo list. */
  async function undoTo(index: number) {
    const r = await act("undo", { steps: index + 1 });
    if (r) toast(`Undid ${index + 1} edit${index ? "s" : ""}`);
  }
  async function putBack(e: Event, force = false) {
    const ok = await run(() => call("app_history_revert", { id: e.id, ...(force ? { force: true } : {}) }), "History");
    if (ok) {
      toast("The team standards are back as they were before that change");
      checkRules();
      checkTargets();
      load();
    }
  }
  const byWho = (by: string) => (by === "you" ? "You" : by);
  /** The edit as the team reads it ("app_edit (component=...)" from the undo list made easier). */
  const readable = (w: string) => plain(w.replace(/^app_/, "").replace(/_/g, " "));

  function eventItems(e: Event): MenuItem[] {
    return [
      { header: KIND[e.kind]?.label ?? e.kind },
      e.kind === "standards" ? { label: "Put the standards back as before this", run: () => putBack(e) } : null,
      e.kind === "standards" ? { label: "Put back anyway (also undoes later changes)", danger: true, run: () => putBack(e, true) } : null,
      e.file ? { label: "Copy the file's path", run: () => copyText(e.file!).then((ok) => ok && toast("Copied")) } : null,
      { label: "Copy this line", run: () => copyText(`${new Date(e.time).toLocaleString()}  ${byWho(e.by)}  ${e.design ?? "Team"}: ${e.what}`).then((ok) => ok && toast("Copied")) },
    ].filter(Boolean) as MenuItem[];
  }
  pageMenu(() => [
    { label: "This design only", checked: scope === "design", off: !app.design && "No design open", run: () => (scope = "design") },
    { label: "Everything", checked: scope === "all", run: () => (scope = "all") },
    { label: "Refresh", run: load },
  ]);
</script>

<div class="page">
  <div class="col">
    {#if app.design && scope === "design"}
      <Panel id="history.since" icon={Clock} title="Since the last save">
        {#snippet status()}<span class="meta">{h?.unsaved ? `${h?.sinceSave?.length ?? 0} change${(h?.sinceSave?.length ?? 0) === 1 ? "" : "s"}` : "Saved"}</span>{/snippet}
        <div class="body">
          {#if !h?.unsaved}
            <p class="muted pad">No changes since {app.design.name} was last saved.</p>
          {:else if !h?.sinceSave?.length}
            <p class="muted pad">Changed, but the edits were made before this history began.</p>
          {:else}
            <ul class="list">
              {#each h.sinceSave as e (e.id)}
                <li use:context={() => eventItems(e)}>
                  <span class="t">{when(e.time)}</span>
                  <span class="who" title={byWho(e.by)}>{#if e.by === "you"}<User size={13} />{:else}<Bot size={13} />{/if}</span>
                  <span class="w">{e.what}</span>
                </li>
              {/each}
            </ul>
          {/if}
        </div>
      </Panel>
      <Panel id="history.undo" icon={Undo2} title="Can be undone">
        {#snippet status()}<span class="meta">This session</span>{/snippet}
        <div class="body">
          {#if !h?.undoable?.length}
            <p class="muted pad">Nothing to undo in this session.</p>
          {:else}
            <ul class="list">
              {#each h.undoable as u, i (i)}
                <li>
                  <span class="t">{clock(u.when)}</span>
                  <span class="w">{readable(u.what)}</span>
                  <button class="btn small" onclick={() => undoTo(i)} title="Undo this and every edit after it">{i === 0 ? "Undo" : `Undo to here (${i + 1})`}</button>
                </li>
              {/each}
            </ul>
          {/if}
        </div>
      </Panel>
    {/if}
  </div>
  <div class="col wide">
    <Panel id="history.all" icon={History} title="History" fill>
      {#snippet tools()}
        <div class="seg" role="group" aria-label="Whose history">
          <button class:on={scope === "design"} disabled={!app.design} onclick={() => (scope = "design")}>{app.design ? app.design.name : "This design"}</button>
          <button class:on={scope === "all"} onclick={() => (scope = "all")}>Everything</button>
        </div>
      {/snippet}
      <div class="body" class:stale={loading}>
        <div class="kinds" role="group" aria-label="Show">
          {#each Object.entries(KIND) as [k, v] (k)}
            <button class="chip" class:on={kinds.includes(k)} onclick={() => (kinds = kinds.includes(k) ? kinds.filter((x) => x !== k) : [...kinds, k])}>{v.label}</button>
          {/each}
          {#if kinds.length}<button class="link" onclick={() => (kinds = [])}>All kinds</button>{/if}
        </div>
        {#if h && !h.kept}
          <p class="muted pad">The history is kept by the desktop app.</p>
        {:else if !days.length}
          <p class="muted pad">Nothing yet. Edits, saves and changes to the team standards show here as they happen, and stay in the designs folder's history.</p>
        {:else}
          {#each days as d (d.day)}
            <h4 class="day">{d.day}</h4>
            <ul class="list timeline">
              {#each d.events as e (e.id)}
                {@const K = KIND[e.kind] ?? KIND.edit}
                <li class={e.kind} use:context={() => eventItems(e)}>
                  <span class="t">{new Date(e.time).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</span>
                  <span class="k" title={K.label}><K.icon size={14} /></span>
                  <span class="w">
                    {#if scope === "all"}<b>{e.design ?? "Team standards"}</b>{" "}{/if}{e.what}
                    <span class="by">{byWho(e.by)}</span>
                  </span>
                  {#if e.kind === "standards"}<button class="btn small" onclick={() => putBack(e)} title="Put the team standards back as they were before this change">Put back</button>{/if}
                </li>
              {/each}
            </ul>
          {/each}
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
    grid-template-columns: minmax(320px, 1fr) minmax(0, 2fr);
    gap: var(--gap);
    padding: var(--gap);
    overflow: auto;
  }
  @media (max-width: 1000px) {
    .page {
      grid-template-columns: 1fr;
    }
  }
  .col {
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: var(--gap);
  }
  .body {
    overflow: auto;
  }
  .body.stale {
    opacity: 0.7;
  }
  .pad {
    margin: 0;
    padding: 12px 14px;
  }
  .meta {
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
  }
  .list {
    list-style: none;
    margin: 0;
    padding: 4px 0;
  }
  .list li {
    display: flex;
    align-items: baseline;
    gap: 10px;
    padding: calc(var(--cell-y) * 0.75) 14px;
    border-bottom: 1px solid var(--line);
  }
  .list li:last-child {
    border-bottom: 0;
  }
  .t {
    flex: none;
    min-width: 4.2em;
    white-space: nowrap;
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
    font-variant-numeric: tabular-nums;
  }
  .who,
  .k {
    flex: none;
    color: var(--ink-3);
    align-self: center;
    display: inline-flex;
  }
  .w {
    flex: 1;
    min-width: 0;
    overflow-wrap: anywhere;
  }
  .by {
    margin-left: 6px;
    font-size: calc(11.5px * var(--text));
    color: var(--ink-3);
  }
  li.standards .k {
    color: var(--select);
  }
  li.save .k {
    color: var(--good);
  }
  .day {
    margin: 0;
    padding: 10px 14px 2px;
    font-size: calc(12px * var(--text));
    color: var(--ink-2);
  }
  .kinds {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    padding: 8px 14px;
    border-bottom: 1px solid var(--line);
  }
  .chip {
    padding: 2px 10px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: transparent;
    color: var(--ink-2);
    font-size: calc(12px * var(--text));
  }
  .chip.on {
    background: var(--select-soft);
    border-color: var(--select);
    color: var(--ink);
  }
  .btn.small {
    height: calc(24px * var(--text));
    padding: 0 8px;
    font-size: calc(12px * var(--text));
    flex: none;
  }
  .seg {
    display: inline-flex;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    overflow: hidden;
  }
  .seg button {
    border: 0;
    border-left: 1px solid var(--line);
    background: transparent;
    padding: 2px 10px;
    font-size: calc(12px * var(--text));
    color: var(--ink-2);
    max-width: 16em;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .seg button:first-child {
    border-left: 0;
  }
  .seg button.on {
    background: var(--select);
    color: #fff;
  }
  .link {
    border: 0;
    padding: 0;
    background: none;
    color: var(--select);
    text-decoration: underline;
    cursor: pointer;
    font-size: calc(12px * var(--text));
  }
</style>
