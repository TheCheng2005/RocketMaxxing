<script lang="ts">
  import { onMount } from "svelte";
  import { connected, handedFiles, launchFiles } from "./lib/api";
  import { app, init, run, undo, redo, save, openDialog, openPath, removeSelected, move, copySelected, paste, duplicate, type Page } from "./lib/state.svelte";
  import { rememberPage, lastPage } from "./lib/lastPage";
  import TopBar from "./lib/TopBar.svelte";
  import NavRail from "./lib/NavRail.svelte";
  import Home from "./lib/Home.svelte";
  import type { Component } from "svelte";
  import Toasts from "./lib/Toasts.svelte";
  import RocketLoader from "./lib/RocketLoader.svelte";
  import AskDialog from "./lib/AskDialog.svelte";
  import { issues, watchPageErrors } from "./lib/issues/issues.svelte";
  import { assistant, loadSettings } from "./lib/assistant/assistant.svelte";
  import { ui } from "./lib/search/ui.svelte";
  import { tour } from "./lib/tour/session.svelte";
  import Tour from "./lib/tour/Tour.svelte";
  import ContextMenu from "./lib/menu/ContextMenu.svelte";
  import UpdateCard from "./lib/UpdateCard.svelte";
  import { checkAtStart } from "./lib/updates.svelte";
  import { openMenu, nativeHere, currentPageItems } from "./lib/menu/menu.svelte";
  import { openIssues } from "./lib/issues/issues.svelte";
  import { keys, MAC } from "./lib/keys";

  let ready = $state(false);

  // Each page's code (three.js for the design view, the plots, the analyses) loads when it is first shown, so the
  // start screen comes up on a small script; the rest is fetched in the background once the app has started.
  type Lazy = () => Promise<{ default: Component }>;
  const PAGE_CODE: Record<Exclude<Page, "home">, Lazy> = {
    design: () => import("./lib/design/DesignPage.svelte"),
    motors: () => import("./lib/motors/MotorsPage.svelte"),
    flights: () => import("./lib/flights/FlightsPage.svelte"),
    flightdata: () => import("./lib/flightdata/FlightDataPage.svelte"),
    standards: () => import("./lib/standards/StandardsPage.svelte"),
    targets: () => import("./lib/targets/TargetsPage.svelte"),
    analysis: () => import("./lib/analysis/AnalysisPage.svelte"),
  };
  const issuesCode: Lazy = () => import("./lib/issues/IssuesDrawer.svelte");
  const assistantCode: Lazy = () => import("./lib/assistant/AssistantDrawer.svelte");
  const searchCode: Lazy = () => import("./lib/search/Search.svelte");
  const guideCode: Lazy = () => import("./lib/guide/Guide.svelte");
  const loaded = new Map<Lazy, Promise<{ default: Component }>>();
  const load = (f: Lazy) => {
    if (!loaded.has(f)) loaded.set(f, f());
    return loaded.get(f)!;
  };
  function prefetch() {
    for (const f of [PAGE_CODE.design, ...Object.values(PAGE_CODE), issuesCode, assistantCode, searchCode, guideCode]) load(f).catch(() => {});
  }
  const PAGES: Page[] = ["home", "design", "motors", "flights", "flightdata", "targets", "standards", "analysis"];
  /** The start: designs handed over meanwhile wait for it. */
  let started: Promise<unknown> = Promise.resolve();

  onMount(() => {
    if (!connected) return;
    watchPageErrors();
    started = run(init)
      .then(async () => {
        // Designs double-clicked to start the app, or given to it on the command line; else the page shown last.
        for (const f of launchFiles) await openPath(f);
        const last = lastPage() as Page | null;
        if (launchFiles.length && app.design) app.page = "design";
        else if (last && PAGES.includes(last) && app.design) app.page = last;
      })
      .finally(() => {
        ready = true;
        setTimeout(prefetch, 300);
        checkAtStart();
      });
    loadSettings();
  });

  $effect(() => {
    if (ready) rememberPage(app.page);
  });

  // A design double-clicked, or given to a second launch, while this window is open.
  function onHashChange() {
    const files = handedFiles();
    if (!connected || !files.length) return;
    started.then(async () => {
      for (const f of files) await openPath(f);
      if (app.design) app.page = "design";
    });
  }

  function typing(e: KeyboardEvent) {
    const t = e.target as HTMLElement;
    return t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable;
  }

  function onKey(e: KeyboardEvent) {
    const mod = e.ctrlKey || e.metaKey;
    if (mod && e.key.toLowerCase() === "z" && !typing(e)) {
      e.preventDefault();
      e.shiftKey ? redo() : undo();
    } else if (mod && e.key.toLowerCase() === "y" && !typing(e)) {
      e.preventDefault();
      redo();
    } else if (mod && e.key.toLowerCase() === "s") {
      e.preventDefault();
      save(e.shiftKey);
    } else if (mod && e.key.toLowerCase() === "k") {
      e.preventDefault();
      ui.search = !ui.search;
    } else if (mod && e.key.toLowerCase() === "o") {
      e.preventDefault();
      openDialog();
    } else if (e.key === "?" && !typing(e) && !overlay()) {
      e.preventDefault();
      ui.guide = "tips";
    } else if (app.page === "design" && !typing(e) && !overlay()) {
      // The parts: delete, move, copy and paste, as in a file list. Not while a dialog or the tour is on top.
      const k = e.key.toLowerCase();
      if (e.key === "Delete" || e.key === "Backspace") removeSelected();
      else if (e.altKey && (e.key === "ArrowUp" || e.key === "ArrowDown")) move(e.key === "ArrowUp" ? -1 : 1);
      else if (mod && k === "c" && !String(getSelection() ?? "")) copySelected();
      else if (mod && k === "v") paste();
      else if (mod && k === "d") duplicate();
      else return;
      e.preventDefault();
    }
  }

  const PAGE_NAMES: Record<Page, string> = {
    home: "Start", design: "Design", motors: "Motors", flights: "Flights", flightdata: "Flight data", targets: "Targets",
    standards: "Standards", analysis: "Analysis",
  };

  // A right-click with no menu of its own where it landed: the page's quick commands, then the app's.
  function onContext(e: MouseEvent) {
    if (e.defaultPrevented || nativeHere(e) || overlay() || !connected) return;
    e.preventDefault();
    const d = app.design;
    openMenu(e.clientX, e.clientY, [
      { header: PAGE_NAMES[app.page] },
      ...currentPageItems(),
      "-",
      { label: "Search…", shortcut: keys("K"), run: () => (ui.search = true) },
      { label: "Undo", shortcut: keys("Z"), off: d?.canUndo ? false : "Nothing to undo", run: undo },
      { label: "Redo", shortcut: MAC ? keys("Z", true) : keys("Y"), off: d?.canRedo ? false : "Nothing to redo", run: redo },
      { label: "Save", shortcut: keys("S"), off: d ? false : "No design open", run: () => save(false) },
      { label: "Save as…", shortcut: keys("S", true), off: d ? false : "No design open", run: () => save(true) },
      "-",
      { label: "About this page (Guide)", run: () => (ui.guide = "pages") },
      { label: "Problems", run: openIssues },
      { label: "Settings", run: () => (ui.settings = "") },
    ]);
  }

  /** Whether a dialog, the search or the tour covers the page (its keys are its own). */
  function overlay() {
    return !!document.querySelector('.veil, [role="dialog"], [aria-modal="true"]');
  }
</script>

<svelte:window onkeydown={onKey} oncontextmenu={onContext} onhashchange={onHashChange} />

{#if !connected}
  <div class="offline">
    <h1>RocketMaxxing</h1>
    <p>Start RocketMaxxing with <code>rocketmaxxing</code>. It opens this window itself.</p>
  </div>
{:else}
  <div class="shell">
    <TopBar />
    <div class="body">
      <NavRail />
      <main>
        {#if !ready}
          <div class="loading"><RocketLoader text="Starting OpenRocket and loading its motors and parts" /></div>
        {:else if app.page === "home" || !app.design}
          <Home />
        {:else}
          {#key app.page}
            {#await load(PAGE_CODE[app.page])}
              <div class="loading"><RocketLoader size="small" /></div>
            {:then m}
              <m.default />
            {/await}
          {/key}
        {/if}
      </main>
      {#if issues.open}{#await load(issuesCode) then m}<m.default />{/await}{/if}
      {#if assistant.open}{#await load(assistantCode) then m}<m.default />{/await}{/if}
    </div>
  </div>
  <Toasts />
  <AskDialog />
  {#if ui.search}{#await load(searchCode) then m}<m.default />{/await}{/if}
  {#if ui.guide !== null}{#await load(guideCode) then m}<m.default />{/await}{/if}
  {#if tour.name}<Tour />{/if}
  <ContextMenu />
  <UpdateCard />
{/if}

<style>
  .shell {
    display: flex;
    flex-direction: column;
    height: 100%;
  }
  .body {
    flex: 1;
    display: flex;
    min-height: 0;
  }
  main {
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
  .loading {
    margin: auto;
    color: var(--ink-3);
    font-size: 16px;
  }
  .offline {
    max-width: 520px;
    margin: 15vh auto;
    padding: 0 16px;
    text-align: center;
  }
  .offline code {
    background: var(--surface-3);
    padding: 2px 6px;
    border-radius: 5px;
  }
</style>
