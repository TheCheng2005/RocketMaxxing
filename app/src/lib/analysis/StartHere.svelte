<script lang="ts">
  import { CATALOG } from "./catalog";
  import { GUIDES } from "./guides";
  import { analysis } from "./store.svelte";
  import { QUESTIONS, REVIEW } from "../guide/content";
  import { ui } from "../search/ui.svelte";
  import { keys } from "../keys";

  // The Analysis page's first view: the analyses by the question they answer, and a design review in order.
  let { choose }: { choose: (tool: string) => void } = $props();
  const NAMES = Object.fromEntries(CATALOG.flatMap((g) => g.items).map((a) => [a.tool, a.label]));
</script>

<div class="start">
  <p class="lead">
    Pick the question you have. Each analysis opens with a one-line summary and an ⓘ that says what it needs before it
    runs (often a motor and the team standards) and what comes back. A dot in the list on the left marks one that has a
    result. Not sure? Ask the search ({keys("K")}) in your own words, or open the <button class="link" onclick={() => (ui.guide = "analyses")}>Guide</button>.
  </p>
  <div class="qs">
    {#each QUESTIONS as g (g.q)}
      <section>
        <h4>{g.q}</h4>
        <p class="why">{g.why}</p>
        <ul>
          {#each g.tools as [tool, why] (tool)}
            <li>
              <button class="name" onclick={() => choose(tool)} title={GUIDES[tool]?.needs.join(" ") ?? ""}>{NAMES[tool] ?? tool}</button>
              <span class="muted">{why}</span>
              {#if analysis.results[tool]}<span class="ran" title="Has a result"></span>{/if}
            </li>
          {/each}
        </ul>
      </section>
    {/each}
  </div>
  <section class="review">
    <h4>A design review, in order</h4>
    <p class="why">Each step builds on the one before; fixing a rule failure first saves running the rest twice.</p>
    <ol>
      {#each REVIEW as [tool, why] (tool)}
        <li><button class="name" onclick={() => choose(tool)}>{NAMES[tool] ?? tool}</button> <span class="muted">{why}</span></li>
      {/each}
    </ol>
  </section>
</div>

<style>
  .start {
    padding: 8px 14px 16px;
    line-height: 1.5;
  }
  .lead {
    margin: 0 0 12px;
    color: var(--ink-2);
    max-width: 900px;
  }
  .qs {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
    gap: 10px;
  }
  section {
    border: 1px solid var(--line);
    border-radius: var(--radius);
    padding: 8px 12px 10px;
    background: var(--surface);
  }
  h4 {
    margin: 0;
    font-size: calc(13.5px * var(--text));
  }
  .why {
    margin: 0 0 4px;
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
  }
  ul,
  ol {
    margin: 0;
    padding-left: 16px;
    font-size: calc(12.5px * var(--text));
  }
  li {
    margin: 2px 0;
  }
  .name {
    border: 0;
    background: transparent;
    padding: 0;
    color: var(--select);
    font-weight: 500;
    text-align: left;
  }
  .name:hover {
    text-decoration: underline;
  }
  .name + .muted::before {
    content: "— ";
  }
  .review {
    margin-top: 10px;
  }
  .ran {
    display: inline-block;
    width: 6px;
    height: 6px;
    margin-left: 4px;
    border-radius: 50%;
    background: var(--ink-3);
    vertical-align: middle;
  }
  .link {
    border: 0;
    background: transparent;
    padding: 0;
    color: var(--select);
    text-decoration: underline;
  }
</style>
