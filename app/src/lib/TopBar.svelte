<script lang="ts">
  import { FilePlus2, FolderOpen, Save, Undo2, Redo2, Settings as Gear, TriangleAlert, Search, Bot, CircleHelp, ChevronDown } from "@lucide/svelte";
  import { app, newDesign, openDialog, save, undo, redo, selectConfig } from "./state.svelte";
  import Settings from "./Settings.svelte";
  import RocketLoader from "./RocketLoader.svelte";
  import { assistant, who } from "./assistant/assistant.svelte";
  import { issues, openIssues, unseen } from "./issues/issues.svelte";
  import { keys, MAC } from "./keys";
  import { ui } from "./search/ui.svelte";

  import { openMenu } from "./menu/menu.svelte";

  /** Save as, and copies in the other programs' formats, under the arrow beside Save. */
  function saveMenu(e: MouseEvent) {
    const box = (e.currentTarget as HTMLElement).parentElement!.getBoundingClientRect();
    openMenu(box.left, box.bottom + 2, [
      { label: "Save", shortcut: keys("S"), run: () => save(false) },
      { label: "Save as…", shortcut: keys("S", true), run: () => save(true) },
      "-",
      { header: "A copy for another program" },
      { label: "RockSim (.rkt)…", run: () => save("rkt") },
      { label: "RASAero II (.CDX1)…", run: () => save("cdx1") },
    ]);
  }

  let fresh = $derived(unseen());
  let watch = $derived(app.design ? app.flightIssues.length : 0);
</script>

<header>
  <div class="brand">
    <img src="./favicon.svg" alt="" width="20" height="20" />
    <span>RocketMaxxing</span>
  </div>

  <div class="group">
    <button class="btn ghost" onclick={newDesign} title="New design"><FilePlus2 size={16} /> New</button>
    <button class="btn ghost" onclick={openDialog} title="Open ({keys('O')})"><FolderOpen size={16} /> Open</button>
    <div class="split">
      <button class="btn ghost" onclick={() => save(false)} disabled={!app.design} title="Save ({keys('S')})">
        <Save size={16} /> Save
      </button>
      <button class="btn ghost more" onclick={saveMenu} disabled={!app.design} aria-haspopup="menu" aria-label="Save as, and other file types"
        title="Save as ({keys('S', true)}), or a copy for RockSim or RASAero"><ChevronDown size={14} /></button>
    </div>
  </div>
  <span class="sep"></span>
  <div class="group">
    <button class="btn ghost icon" onclick={undo} disabled={!app.design?.canUndo} title="Undo ({keys('Z')})"><Undo2 size={16} /></button>
    <button class="btn ghost icon" onclick={redo} disabled={!app.design?.canRedo} title="Redo ({MAC ? keys('Z', true) : keys('Y')})"><Redo2 size={16} /></button>
  </div>

  {#if app.design}
    <div class="title" title={app.design.path ?? "Not saved yet"}>
      <span class="name">{app.design.name}</span>
      {#if !app.design.saved}<span class="edited">(edited)</span>{/if}
      {#if app.design.importReport}
        {@const n = app.design.importReport.findings.filter((f) => f.level !== "note").length}
        <button class="imported" class:warn={n > 0} onclick={() => (ui.importReport = true)}
          title="What came in from {app.design.importReport.file}, what was left out and what to check">
          {app.design.importReport.format === "OpenRocket" ? "Opened with changes" : `Imported from ${app.design.importReport.format}`}{n ? ` · ${n} to check` : ""}
        </button>
      {/if}
    </div>
  {/if}

  <div class="spacer"></div>

  <button class="find" onclick={() => (ui.search = true)} title="Find an analysis, page, part or setting ({keys('K')})">
    <Search size={15} /><span>Search</span><kbd>{keys("K")}</kbd>
  </button>

  {#if app.busy > 0}<span class="busy" title="Working"><RocketLoader size="tiny" /></span>{/if}

  {#if app.design}
    <label class="config" data-tour="config">
      <span>Configuration</span>
      <select class="select" value={app.design.selected} onchange={(e) => selectConfig((e.target as HTMLSelectElement).value)}>
        {#each app.design.configurations as c (c.id)}
          <option value={c.id}>{c.name}</option>
        {/each}
      </select>
    </label>
  {/if}

  <button class="btn ghost bot" class:on={assistant.open} onclick={() => (assistant.open = !assistant.open)}
    title={assistant.settings?.enabled ? `Ask ${who()} about the design` : "AI assistant: set it up and ask about the design"}
    aria-label="AI assistant" aria-pressed={assistant.open}>
    <Bot size={18} /><span>{assistant.settings?.enabled ? who() : "Assistant"}{#if assistant.running}…{/if}</span>
  </button>
  <button
    class="btn ghost problems"
    class:on={issues.open}
    class:bad={fresh > 0}
    class:warn={fresh === 0 && watch > 0}
    onclick={() => (issues.open ? (issues.open = false) : openIssues())}
    title={fresh ? `${fresh} new error${fresh === 1 ? "" : "s"}` : watch ? `${watch} thing${watch === 1 ? "" : "s"} to watch in the flight` : "Problems: errors and warnings"}
  >
    <TriangleAlert size={16} />{#if fresh || watch}<span class="count">{fresh || watch}</span>{/if}
  </button>
  <button class="btn ghost icon" onclick={() => (ui.guide = "start")} title="Guide: how to use RocketMaxxing" aria-label="Guide"><CircleHelp size={17} /></button>
  <button class="btn ghost icon" onclick={() => (ui.settings = "")} title="Settings: units, theme, assistant"><Gear size={17} /></button>
</header>

{#if ui.settings !== null}
  <Settings section={ui.settings} close={() => (ui.settings = null)} />
{/if}

<style>
  .find {
    display: flex;
    align-items: center;
    gap: 6px;
    width: 220px;
    height: 28px;
    margin-right: 8px;
    padding: 0 6px 0 9px;
    border: 1px solid var(--chrome-line);
    border-radius: 6px;
    background: var(--chrome-2);
    color: var(--chrome-ink-2);
    font: inherit;
    font-size: 13px;
    text-align: left;
  }
  .find:hover {
    color: var(--chrome-ink);
    border-color: var(--chrome-ink-2);
  }
  .find span {
    flex: 1;
  }
  .find kbd {
    font: inherit;
    font-size: 11px;
    padding: 0 4px;
    border: 1px solid var(--chrome-line);
    border-radius: 4px;
  }
  @media (max-width: 1100px) {
    .bot span {
      display: none;
    }
    .find {
      width: auto;
    }
    .find span,
    .find kbd {
      display: none;
    }
  }
  .problems {
    gap: 3px;
    padding: 0 7px;
  }
  .count {
    font-size: 11.5px;
    font-weight: 600;
    line-height: 16px;
    min-width: 16px;
    padding: 0 4px;
    border-radius: 8px;
    text-align: center;
  }
  /* A badge, so it reads on any scheme's bar. */
  .problems.bad .count {
    background: var(--bad);
    color: #fff;
  }
  .problems.warn .count {
    background: var(--warn);
    color: #1d1600;
  }
  .btn.on {
    background: var(--chrome-3);
    color: var(--chrome-ink);
  }
  /* Buttons on the bar take the chrome's colours (a colour scheme may make it dark). */
  header > .btn.ghost,
  .group > .btn.ghost {
    color: var(--chrome-ink);
  }
  header > .btn.ghost:hover:not(:disabled),
  .group > .btn.ghost:hover:not(:disabled) {
    background: var(--chrome-2);
  }
  header {
    height: 40px;
    flex: none;
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 0 8px;
    background: var(--chrome);
    color: var(--chrome-ink);
    border-bottom: 1px solid var(--chrome-line);
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 7px;
    font-weight: 600;
    padding-right: 10px;
    margin-right: 2px;
    border-right: 1px solid var(--chrome-line);
  }
  .group {
    display: flex;
    gap: 1px;
  }
  .sep {
    width: 1px;
    height: 20px;
    background: var(--chrome-line);
  }
  .icon {
    padding: 0 7px;
  }
  .title {
    display: flex;
    align-items: baseline;
    gap: 6px;
    min-width: 0;
    margin-left: 8px;
  }
  .name {
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .edited {
    color: var(--chrome-ink-2);
    white-space: nowrap;
  }
  .spacer {
    flex: 1;
  }
  .busy {
    display: flex;
    color: var(--chrome-ink-2);
    animation: spin 1s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  .config {
    display: flex;
    align-items: center;
    gap: 6px;
    color: var(--chrome-ink-2);
  }
  .config .select {
    max-width: 240px;
    color: var(--ink);
  }
  @media (max-width: 1200px) {
    .config > span {
      display: none;
    }
  }
  .split {
    display: flex;
    align-items: center;
  }
  .split .more {
    padding: 0 3px;
    margin-left: -4px;
    min-width: 0;
  }
  .title .name {
    flex: 0 1 auto;
    min-width: 9ch;
  }
  .imported {
    flex: 0 2 auto;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    margin-left: 8px;
    padding: 1px 8px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: transparent;
    color: var(--ink-2);
    font-size: 12px;
    white-space: nowrap;
    cursor: pointer;
  }
  .imported.warn {
    border-color: transparent;
    background: var(--warn-soft);
    color: var(--warn);
  }
</style>
