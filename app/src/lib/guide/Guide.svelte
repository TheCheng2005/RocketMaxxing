<script lang="ts">
  import { ArrowRight } from "@lucide/svelte";
  import Modal from "../Modal.svelte";
  import { app, type Page } from "../state.svelte";
  import { analysis } from "../analysis/store.svelte";
  import { CATALOG } from "../analysis/catalog";
  import { ui } from "../search/ui.svelte";
  import { designTour } from "../tour/tours";
  import { PAGE_HELP, QUESTIONS, REVIEW, STD_SECTIONS, STEPS, TIPS } from "./content";

  // How to use RocketMaxxing: a first session step by step, what each page is for, which analysis answers which
  // question, what the team standards hold, and shortcuts. Every step and analysis named here can be opened from it.
  const TABS: [string, string][] = [
    ["start", "Getting started"],
    ["pages", "The pages"],
    ["analyses", "Which analysis?"],
    ["standards", "Team standards"],
    ["tips", "Shortcuts and tips"],
  ];
  let tab = $state(ui.guide && TABS.some(([t]) => t === ui.guide) ? ui.guide : "start");
  const NAMES = Object.fromEntries(CATALOG.flatMap((g) => g.items).map((a) => [a.tool, a.label]));
  const SECTION_NAMES: Record<string, string> = {
    competition: "Competition", launchSite: "Launch site", recovery: "Recovery", structures: "Structures and materials", electronics: "Electronics",
  };

  function close() {
    ui.guide = null;
  }
  const blocked = (page?: Page) => !!page && page !== "home" && !app.design;

  function go(page?: Page, tool?: string) {
    if (tool) {
      analysis.selected = tool;
      page = "analysis";
    }
    if (!page || blocked(page)) return;
    app.page = page;
    close();
  }
</script>

{#snippet open(page: Page | undefined, tool: string | undefined, text: string)}
  <button class="go" disabled={blocked(tool ? "analysis" : page)} title={blocked(tool ? "analysis" : page) ? "Open a design first" : ""}
    onclick={() => go(page, tool)}>{text} <ArrowRight size={13} /></button>
{/snippet}

<Modal title="Guide" {close} width={860}>
  <div class="guide">
    <nav>
      {#each TABS as [id, name] (id)}
        <button class:on={tab === id} onclick={() => (tab = id)}>{name}</button>
      {/each}
    </nav>
    <div class="body">
      {#if tab === "start"}
        <p class="lead">A first session with a design, in order. Each step opens where it happens.</p>
        <ol class="steps">
          {#each STEPS as s, i (i)}
            <li>
              <div><b>{s.title}</b><p>{s.text}</p></div>
              {#if s.page || s.analysis}{@render open(s.page, s.analysis, "Open")}{/if}
            </li>
          {/each}
        </ol>
        {#if !app.design}<p class="muted">The steps after the first need an open design.</p>{/if}
        <div class="tourline">
          <span><b>Prefer to be shown?</b> The Design page tour points at each part of the page in turn.</span>
          <button class="go" disabled={!app.design} title={app.design ? "" : "Open a design first"} onclick={() => designTour()}>Take the Design tour <ArrowRight size={13} /></button>
        </div>
      {:else if tab === "pages"}
        <p class="lead">The bar on the left has one button per page.</p>
        <dl class="pages">
          {#each PAGE_HELP as p (p.page)}
            <dt>{p.title}</dt>
            <dd><span>{p.text}</span>{@render open(p.page, undefined, "Go")}</dd>
          {/each}
        </dl>
      {:else if tab === "analyses"}
        <p class="lead">
          Find the question you have; the analyses under it answer it. Each analysis's ⓘ says what it needs before it
          runs and what comes back.
        </p>
        <div class="qs">
          {#each QUESTIONS as g (g.q)}
            <section>
              <h4>{g.q}</h4>
              <p class="muted small">{g.why}</p>
              <ul>
                {#each g.tools as [tool, why] (tool)}
                  <li><button class="name" disabled={blocked("analysis")} onclick={() => go(undefined, tool)}>{NAMES[tool] ?? tool}</button> <span class="muted">— {why}</span></li>
                {/each}
              </ul>
            </section>
          {/each}
        </div>
        <h4>A design review, in order</h4>
        <ol class="review">
          {#each REVIEW as [tool, why] (tool)}
            <li><button class="name" disabled={blocked("analysis")} onclick={() => go(undefined, tool)}>{NAMES[tool] ?? tool}</button> <span class="muted">{why}</span></li>
          {/each}
        </ol>
      {:else if tab === "standards"}
        <p class="lead">
          The team standards are your team's numbers: the rules you compete under, your launch site, safety factors,
          shear pin ratings and material data. Every check reads them, so a value changed here changes every result
          that uses it. They are kept in one file next to your designs (<code>openrocket-mcp.json</code>), which the
          assistant reads too.
        </p>
        <ul class="how">
          <li>A value with a dot (●) is your team's; the rest are built-in defaults. ↺ returns a value to its default.</li>
          <li>Type values with units (18.5 ft, 140 N). Named tables (shear pins, materials) take new rows with Add.</li>
          <li>Material rows match the material names in the design: “fiberglass|g10” applies to either name.</li>
          <li>Each section and value has an ⓘ on the page with what it means and which analyses use it.</li>
        </ul>
        {#each Object.entries(STD_SECTIONS) as [key, s] (key)}
          <section class="std">
            <h4>{SECTION_NAMES[key]}</h4>
            <p><b>Holds:</b> {s.holds}</p>
            <p><b>Used by:</b> {s.usedBy}</p>
          </section>
        {/each}
        {@render open("standards", undefined, "Open the Standards page")}
      {:else}
        <dl class="tips">
          {#each TIPS as [k, text] (k)}
            <dt><kbd>{k}</kbd></dt>
            <dd>{text}</dd>
          {/each}
        </dl>
      {/if}
    </div>
  </div>
</Modal>

<style>
  .guide {
    display: flex;
    min-height: 420px;
    max-height: calc(100vh - 120px);
  }
  nav {
    flex: none;
    width: 170px;
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 10px 8px;
    border-right: 1px solid var(--line);
  }
  nav button {
    border: 0;
    background: transparent;
    text-align: left;
    padding: 6px 10px;
    border-radius: 5px;
    color: var(--ink-2);
  }
  nav button:hover {
    background: var(--surface-2);
  }
  nav button.on {
    background: var(--select-soft);
    color: var(--ink);
    font-weight: 600;
  }
  .body {
    flex: 1;
    min-width: 0;
    overflow: auto;
    padding: 12px 18px 16px;
    line-height: 1.5;
  }
  .lead {
    margin: 0 0 10px;
    color: var(--ink-2);
  }
  .steps {
    margin: 0;
    padding-left: 22px;
  }
  .steps li {
    margin-bottom: 10px;
  }
  .steps li,
  .pages dd {
    display: flex;
    gap: 12px;
    align-items: flex-start;
    justify-content: space-between;
  }
  .steps p {
    margin: 2px 0 0;
    color: var(--ink-2);
  }
  .go {
    flex: none;
    display: inline-flex;
    align-items: center;
    gap: 4px;
    border: 1px solid var(--line);
    border-radius: 5px;
    background: var(--surface);
    padding: 3px 9px;
    color: var(--select);
    font-size: calc(12.5px * var(--text));
  }
  .go:hover:not(:disabled) {
    border-color: var(--select);
  }
  .go:disabled {
    color: var(--ink-3);
  }
  .pages {
    margin: 0;
  }
  .tourline {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-top: 8px;
    padding: 8px 12px;
    border-radius: var(--radius);
    background: var(--select-soft);
  }
  .pages dt,
  .tips dt {
    font-weight: 600;
    margin-top: 8px;
  }
  .pages dd,
  .tips dd {
    margin: 2px 0 0;
    color: var(--ink-2);
  }
  .qs {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
    gap: 10px 18px;
  }
  .qs section {
    border: 1px solid var(--line);
    border-radius: var(--radius);
    padding: 8px 12px;
  }
  h4 {
    margin: 4px 0 2px;
    font-size: calc(13.5px * var(--text));
  }
  .small {
    margin: 0 0 4px;
    font-size: calc(12px * var(--text));
  }
  .qs ul {
    margin: 0;
    padding-left: 16px;
    font-size: calc(12.5px * var(--text));
  }
  .name {
    border: 0;
    background: transparent;
    padding: 0;
    color: var(--select);
    font-weight: 500;
    text-align: left;
  }
  .name:hover:not(:disabled) {
    text-decoration: underline;
  }
  .name:disabled {
    color: var(--ink);
  }
  .review {
    margin: 4px 0 0;
    padding-left: 22px;
  }
  .how {
    margin: 0 0 10px;
    padding-left: 18px;
    color: var(--ink-2);
  }
  .std {
    margin: 0 0 8px;
  }
  .std p {
    margin: 2px 0;
    color: var(--ink-2);
  }
  .tips dt {
    margin-top: 10px;
  }
  kbd {
    font: inherit;
    font-size: calc(12px * var(--text));
    padding: 1px 6px;
    border: 1px solid var(--line);
    border-radius: 4px;
    background: var(--surface-2);
  }
  code {
    font-size: calc(12px * var(--text));
  }
  @media (max-width: 700px) {
    .guide {
      flex-direction: column;
    }
    nav {
      width: auto;
      flex-direction: row;
      flex-wrap: wrap;
      border-right: 0;
      border-bottom: 1px solid var(--line);
    }
  }
</style>
