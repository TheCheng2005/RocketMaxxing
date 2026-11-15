<script lang="ts">
  import { Box, Gauge, ListTree, ShieldCheck, SlidersHorizontal, SquarePlus, ArrowUp, ArrowDown, Copy, Trash2, Image, Route, X, Wind } from "@lucide/svelte";
  import { reducedMotion } from "../prefs.svelte";
  import { designTour } from "../tour/tours";
  import { seen, markSeen } from "../tour/session.svelte";
  import PictureDialog from "../media/PictureDialog.svelte";
  import { app, move, canMove, duplicate, removeSelected, findNode } from "../state.svelte";
  import { keys } from "../keys";
  import { isOpen, setOpen } from "../panels.svelte";
  import Panel from "../Panel.svelte";
  import Tree from "./Tree.svelte";
  import Palette from "./Palette.svelte";
  import Inspector from "./Inspector.svelte";
  import RocketView from "./RocketView.svelte";
  import Stats from "./Stats.svelte";
  import RuleCheck from "./RuleCheck.svelte";
  import RuleCheckStatus from "./RuleCheckStatus.svelte";
  import AeroCompare from "./AeroCompare.svelte";
  import { num as fmtNum } from "../units.svelte";

  // Fast flights get RASAero's numbers beside OpenRocket's: from Mach 0.8, or whenever a RASAero table is in.
  let fast = $derived((app.flight?.maxMach ?? 0) >= 0.8 || !!app.design?.aeroTable);
  import { pageMenu } from "../menu/menu.svelte";
  import { paste, clipboard, checkRules } from "../state.svelte";
  import { analysis } from "../analysis/store.svelte";

  import { ui, take } from "../search/ui.svelte";
  $effect(() => {
    void ui.request;
    if (take("picture")) picture = true;
    if (take("aero")) {
      setOpen("design.aero", true);
      setTimeout(() => document.querySelector('[data-panel="design.aero"]')?.scrollIntoView({ block: "start", behavior: reducedMotion() ? "auto" : "smooth" }), 100);
    }
  });

  // Right-click anywhere else on the page.
  pageMenu(() => [
    { label: clipboard.id ? `Paste ${clipboard.name}` : "Paste", shortcut: keys("V"), off: !clipboard.id && "Copy a part first", run: () => paste() },
    { label: "Picture or drawing…", run: () => (picture = true) },
    { label: "Exploded view", run: () => dispatchEvent(new CustomEvent("rocketmaxxing:explode")) },
    { label: "Check the rules now", run: () => checkRules() },
    { label: "Add an avionics bay…", run: () => ((analysis.selected = "add_avionics_bay"), (app.page = "analysis")) },
    { label: "Take the tour", run: () => designTour() },
  ]);

  let isRoot = $derived(app.selected === "rocket");
  let target = $derived(findNode(app.design?.tree, app.selected)?.name ?? "");
  let picture = $state(false);
  // The first time the page is shown, a line above the view offers the tour (once; the View panel keeps a button).
  let offer = $state(!seen("design"));
  function noThanks() {
    markSeen("design");
    offer = false;
  }
</script>

<div class="design">
  <div class="left" class:shut={!isOpen("design.parts")}>
    <Panel id="design.parts" icon={ListTree} title="Parts" side="left">
      {#snippet tools()}
        <button class="tool" title="Move up (Alt+↑)" disabled={!canMove(-1)} onclick={() => move(-1)}><ArrowUp size={15} /></button>
        <button class="tool" title="Move down (Alt+↓)" disabled={!canMove(1)} onclick={() => move(1)}><ArrowDown size={15} /></button>
        <button class="tool" title="Duplicate ({keys('D')})" disabled={isRoot} onclick={duplicate}><Copy size={15} /></button>
        <button class="tool" title="Delete (Del)" disabled={isRoot} onclick={removeSelected}><Trash2 size={15} /></button>
      {/snippet}
      <Tree />
    </Panel>
  </div>

  <div class="center">
    {#if offer}
      <div class="offer" role="note">
        <Route size={16} />
        <span><b>New to the Design page?</b> A one-minute tour shows what each part of it does.</span>
        <button class="btn primary small" onclick={() => { offer = false; designTour(); }}>Take the tour</button>
        <button class="tool" onclick={noThanks} aria-label="Not now" title="Not now (the View panel has a Tour button)"><X size={15} /></button>
      </div>
    {/if}
    <Panel id="design.view" icon={Box} title="View" fill>
      {#snippet tools()}
        <button class="tool wide" title="A guided tour of this page" onclick={() => { offer = false; designTour(); }}><Route size={15} /> Tour</button>
        <button class="tool wide" data-tour="picture" title="Picture or drawing of the design, for reviews and build notes" onclick={() => (picture = true)}><Image size={15} /> Picture</button>
      {/snippet}
      <div class="view"><RocketView picture={() => (picture = true)} /></div>
    </Panel>
    <Panel id="design.summary" icon={Gauge} title="Summary">
      <Stats />
    </Panel>
    {#if fast}
      <Panel id="design.aero" icon={Wind} title="RASAero vs OpenRocket">
        {#snippet status()}
          {#if app.design?.aeroTable}<span class="chip good">RASAero table in</span>
          {:else if app.flight?.maxMach}<span class="chip warn">Mach {fmtNum(app.flight.maxMach, 3)}: no RASAero data</span>{/if}
        {/snippet}
        <AeroCompare />
      </Panel>
    {/if}
    <Panel id="design.rules" icon={ShieldCheck} title="Rule check" initial={false}>
      {#snippet status()}<RuleCheckStatus />{/snippet}
      <RuleCheck />
    </Panel>
    <Panel id="design.add" icon={SquarePlus} title="Add part">
      {#snippet tools()}<span class="to">to {target}</span>{/snippet}
      <Palette />
    </Panel>
  </div>

  <div class="right" class:shut={!isOpen("design.props")}>
    <Panel id="design.props" icon={SlidersHorizontal} title="Properties" side="right">
      <Inspector />
    </Panel>
  </div>
</div>

{#if picture}<PictureDialog close={() => (picture = false)} />{/if}

<style>
  .offer {
    flex: none;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px 8px 6px 12px;
    border: 1px solid var(--select);
    border-radius: var(--radius);
    background: var(--select-soft);
    color: var(--ink);
  }
  .offer span {
    flex: 1;
  }
  .design {
    flex: 1;
    min-height: 0;
    display: flex;
    gap: var(--gap);
    padding: var(--gap);
  }
  .left {
    width: 280px;
    flex: none;
    min-height: 0;
  }
  .right {
    width: 360px;
    flex: none;
    min-height: 0;
  }
  .left.shut,
  .right.shut {
    width: auto;
  }
  .center {
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: var(--gap);
    /* On a short window the column scrolls rather than squeezing panels into each other. */
    overflow-y: auto;
  }
  .center > :global(.panel:not(.fill)) {
    flex: none;
  }
  .view {
    position: relative;
    flex: 1;
    min-height: 200px;
  }
  .center > :global(.panel.fill) {
    min-height: 280px;
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
  .tool:hover:not(:disabled) {
    background: var(--surface-3);
    color: var(--ink);
  }
  .tool.wide {
    width: auto;
    display: flex;
    gap: 4px;
    padding: 0 6px;
    font-size: calc(12px * var(--text));
  }
  .tool:disabled {
    opacity: 0.35;
  }
  .chip {
    font-size: calc(11.5px * var(--text));
    padding: 2px 8px;
    border-radius: 999px;
    white-space: nowrap;
  }
  .chip.good {
    background: var(--good-soft);
    color: var(--good);
  }
  .chip.warn {
    background: var(--warn-soft);
    color: var(--warn);
  }
  .to {
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 300px;
  }
</style>
