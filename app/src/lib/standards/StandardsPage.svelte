<script lang="ts">
  import TeamMaterials from "./TeamMaterials.svelte";
  import { Trophy, MapPin, Umbrella, Wrench, Cpu, Layers } from "@lucide/svelte";
  import { reducedMotion } from "../prefs.svelte";
  const ICONS = { launchSite: MapPin, recovery: Umbrella, structures: Wrench, electronics: Cpu } as Record<string, any>;
  import { onMount } from "svelte";
  import { call } from "../api";
  import { app, run, checkRules, checkTargets } from "../state.svelte";
  import Panel from "../Panel.svelte";
  import StdNode from "./StdNode.svelte";
  import { at, patchFor, type Json } from "./std";
  import Tip from "../Tip.svelte";
  import { isOpen, toggle } from "../panels.svelte";
  import { STD_SECTIONS } from "../guide/content";
  import { ui } from "../search/ui.svelte";
  import { pageMenu } from "../menu/menu.svelte";

  // The team's standards: the competition rule set, the launch site, and the safety factors, ratings and material data
  // the checks use. Only what the team changes is written to its file (openrocket-mcp.json), so it stays short and
  // readable, and Claude reads the same file.
  interface Std {
    file: string;
    fileExists: boolean;
    team: Json;
    merged: Json;
    defaults: Json;
    ruleSets: { id: string; name: string }[];
    ruleSet: string;
  }

  let std = $state<Std | null>(null);

  async function load() {
    std = (await run(() => call<Std>("app_standards"))) ?? null;
  }
  onMount(load);

  async function set(path: string[], value: Json) {
    const ok = await run(() => call("app_set_standards", { patch: patchFor(path, value) }));
    await load();
    if (ok && app.design) {
      checkRules();
      checkTargets();
    }
  }

  const SECTIONS: [string, string][] = [
    ["launchSite", "Launch site"],
    ["recovery", "Recovery"],
    ["structures", "Structures and materials"],
    ["electronics", "Electronics"],
  ];
  const JUMP: [string, string, boolean][] = [
    ["competition", "Competition", true], ["launchSite", "Launch site", true], ["recovery", "Recovery", true],
    ["structures", "Structures and materials", true], ["electronics", "Electronics", false],
  ];

  /** Opens a section if it is folded and brings it into view. */
  function jump(key: string, initial: boolean) {
    if (!isOpen("std." + key, initial)) toggle("std." + key, initial);
    requestAnimationFrame(() => document.getElementById("std-" + key)?.scrollIntoView({ behavior: reducedMotion() ? "auto" : "smooth", block: "start" }));
  }

  // Asked for from the search: open the section and show the value ("recovery.shearPins").
  import { ui as searchUi, take } from "../search/ui.svelte";
  $effect(() => {
    void searchUi.request;
    if (!std) return;
    const r = take("standard");
    if (!r?.detail) return;
    const [section] = r.detail.split(".");
    const j = JUMP.find(([k]) => k === section);
    if (j) jump(j[0], j[2]);
    setTimeout(() => {
      const el = document.querySelector(`[data-std="${r.detail}"]`) as HTMLElement | null;
      if (!el) return;
      if (el instanceof HTMLDetailsElement) el.open = true;
      el.scrollIntoView({ block: "center", behavior: reducedMotion() ? "auto" : "smooth" });
      document.querySelectorAll("[data-std].found").forEach((x) => x.classList.remove("found"));
      void el.offsetWidth;
      el.classList.add("found");
      (el.querySelector("input, select") as HTMLElement | null)?.focus({ preventScroll: true });
    }, 250);
  });

  pageMenu(() => [
    { header: "Go to" },
    ...JUMP.map(([key, name, initial]) => ({ label: name, run: () => jump(key, initial) })),
    "-",
    { label: "Guide to the standards", run: () => (ui.guide = "standards") },
    { label: "Show the standards file", off: !std?.fileExists && "Not written yet: change a value first", run: () => std && run(() => call("app_reveal", { path: std!.file })) },
  ]);

  let ruleset = $derived(std ? String(at(std.merged, ["ruleset"]) ?? "none") : "");
</script>

{#snippet about(key: string)}
  <Tip wide label="What this section is for">
    <p class="tp"><b>Holds:</b> {STD_SECTIONS[key].holds}</p>
    <p class="tp"><b>Used by:</b> {STD_SECTIONS[key].usedBy}</p>
  </Tip>
{/snippet}

<div class="page">
  {#if std}
    <div class="intro">
      <p>
        Your team's numbers, used by every check: the rules you compete under, your launch site, safety factors, pin
        ratings and material data. Change a value here and every analysis that uses it changes. <span class="dot">●</span>
        marks your team's own values, ↺ returns one to the default, and each ⓘ says what a value means and which
        analyses use it. Set the competition, launch site, shear pins and fin material first.
      </p>
      <div class="jump">
        <span class="muted">Jump to</span>
        {#each JUMP as [key, name, initial] (key)}<button onclick={() => jump(key, initial)}>{name}</button>{/each}
        <button class="guide" onclick={() => (ui.guide = "standards")}>Guide to the standards</button>
      </div>
    </div>
    <div class="col">
      <div id="std-competition"></div>
      <Panel id="std.competition" icon={Trophy} title="Competition">
        {#snippet status()}{@render about("competition")}{/snippet}
        <div class="body">
          <div class="row">
            <span>Rule set</span>
            <select class="select" value={ruleset} onchange={(e) => set(["ruleset"], (e.target as HTMLSelectElement).value)}>
              {#each std.ruleSets as r (r.id)}<option value={r.id}>{r.name}</option>{/each}
              {#if !std.ruleSets.some((r) => r.id === ruleset)}<option value={ruleset}>{ruleset}</option>{/if}
            </select>
          </div>
          <div class="row">
            <span>Competition year</span>
            <input class="input" type="number" value={String(at(std.merged, ["competitionYear"]) ?? "")}
              onchange={(e) => set(["competitionYear"], e.currentTarget.value ? Number(e.currentTarget.value) : null)} />
          </div>
          <label class="row">
            <span>Staged category</span>
            <input type="checkbox" checked={!!at(std.merged, ["stagedCategory"])} onchange={(e) => set(["stagedCategory"], e.currentTarget.checked)} />
          </label>
          <p class="muted small">The rule check (Design and Targets pages) uses this rule set. A team can also point to its own rule file: put its path in the team file's "ruleset".</p>
          <p class="muted small">
            {std.fileExists ? "Team file:" : "Not changed yet; changes go to"} <code>{std.file}</code>. <span class="dot">●</span> marks the team's own values;
            the rest are the built-in defaults.
          </p>
        </div>
      </Panel>
      {#each SECTIONS.slice(0, 2) as [key, title] (key)}
        <div id="std-{key}"></div>
        <Panel id="std.{key}" icon={ICONS[key]} {title}>
          {#snippet status()}{@render about(key)}{/snippet}
          <div class="body"><StdNode path={[key]} std={std} {set} /></div>
        </Panel>
      {/each}
    </div>
    <div class="col">
      {#each SECTIONS.slice(2) as [key, title] (key)}
        <div id="std-{key}"></div>
        <Panel id="std.{key}" icon={ICONS[key]} {title} initial={key !== "electronics"}>
          {#snippet status()}{@render about(key)}{/snippet}
          <div class="body"><StdNode path={[key]} std={std} {set} /></div>
        </Panel>
      {/each}
      <div id="std-materials"></div>
      <Panel id="std.materials" icon={Layers} title="Your materials">
        {#snippet status()}<span class="muted small">In every part's material list</span>{/snippet}
        <TeamMaterials />
      </Panel>
    </div>
  {:else}
    <p class="muted pad">Reading the standards…</p>
  {/if}
</div>

<style>
  .page {
    flex: 1;
    min-height: 0;
    overflow: auto;
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: var(--gap);
    padding: var(--gap);
    align-items: start;
  }
  @media (max-width: 1100px) {
    .page {
      grid-template-columns: 1fr;
    }
  }
  .intro {
    grid-column: 1 / -1;
    padding: 8px 12px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface);
  }
  .intro p {
    margin: 0 0 6px;
    color: var(--ink-2);
    max-width: 1000px;
  }
  .jump {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
  }
  .jump button {
    border: 1px solid var(--line);
    border-radius: 12px;
    background: var(--surface-2);
    padding: 2px 10px;
    font-size: calc(12.5px * var(--text));
    color: var(--ink);
  }
  .jump button:hover {
    border-color: var(--select);
  }
  .jump .guide {
    margin-left: auto;
    border: 0;
    background: transparent;
    color: var(--select);
    text-decoration: underline;
  }
  .tp {
    margin: 0 0 5px;
  }
  .col {
    display: flex;
    flex-direction: column;
    gap: var(--gap);
    min-width: 0;
  }
  .body {
    padding: 8px 12px 12px;
  }
  .row {
    display: grid;
    grid-template-columns: minmax(160px, 40%) 1fr 70px;
    align-items: center;
    gap: 8px;
    min-height: 30px;
    color: var(--ink-2);
  }
  .row input[type="checkbox"] {
    justify-self: start;
    width: 16px;
    height: 16px;
  }
  .small {
    font-size: calc(12px * var(--text));
    margin: 6px 0 0;
  }
  .dot {
    color: var(--select);
    font-size: calc(9px * var(--text));
  }
  code {
    font-size: calc(11.5px * var(--text));
    word-break: break-all;
  }
  .pad {
    padding: 12px;
  }
</style>
