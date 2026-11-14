<script lang="ts">
  import { Clock, House, Shapes, FilePlus2, FolderOpen, FileText, CircleHelp } from "@lucide/svelte";
  import { ui } from "./search/ui.svelte";
  import { app, newDesign, openDialog, openExample, openPath, recent } from "./state.svelte";
  import ComponentIcon from "./ComponentIcon.svelte";
  import Panel from "./Panel.svelte";
  import { call } from "./api";
  import { run, toast } from "./state.svelte";
  import { context, pageMenu, copyText, type MenuItem } from "./menu/menu.svelte";

  let recents = $state(recent());

  // Right-click on a recent file: open, show it in its folder, copy its path, take it off the list.
  function forget(p: string) {
    recents = recents.filter((x) => x !== p);
    try {
      localStorage.setItem("rocketmaxxing.recent", JSON.stringify(recents));
    } catch {
      /* not kept */
    }
  }
  function recentItems(p: string): MenuItem[] {
    return [
      { header: p.split(/[\\/]/).pop() ?? p },
      { label: "Open", run: () => openPath(p) },
      { label: "Show in its folder", run: () => run(() => call("app_reveal", { path: p })) },
      { label: "Copy its path", run: () => copyText(p).then((ok) => ok && toast("Copied the path")) },
      { label: "Remove from this list", run: () => forget(p) },
      "-",
      ...pageItems(),
    ];
  }
  function pageItems(): MenuItem[] {
    return [
      { label: "New design…", run: newDesign },
      { label: "Open…", run: openDialog },
      { label: "Guide", run: () => (ui.guide = "start") },
      recents.length ? { label: "Clear the recent list", danger: true, run: () => [...recents].forEach(forget) } : null,
    ].filter(Boolean) as MenuItem[];
  }
  pageMenu(pageItems);

  function iconFor(name: string) {
    const n = name.toLowerCase();
    if (n.includes("parachute") || n.includes("chute")) return "Parachute";
    if (n.includes("stage") || n.includes("airstart")) return "AxialStage";
    if (n.includes("booster") || n.includes("cluster")) return "ParallelStage";
    if (n.includes("pod")) return "PodSet";
    if (n.includes("tube fin")) return "TubeFinSet";
    if (n.includes("payload")) return "MassComponent";
    if (n.includes("nose")) return "NoseCone";
    if (n.includes("drag")) return "Transition";
    return "Rocket";
  }
</script>

<div class="home">
  <div class="col">
    <Panel id="home.start" icon={House} title="Start">
      <div class="actions">
        <button class="act" onclick={newDesign}><FilePlus2 size={22} /> <span><b>New design</b><small>An empty rocket</small></span></button>
        <button class="act" onclick={openDialog}><FolderOpen size={22} /> <span><b>Open…</b><small>OpenRocket .ork, RockSim .rkt or RASAero .CDX1</small></span></button>
        <button class="act" onclick={() => (ui.guide = "start")}><CircleHelp size={22} /> <span><b>Guide</b><small>How to use RocketMaxxing, step by step</small></span></button>
      </div>
    </Panel>
    <Panel id="home.recent" icon={Clock} title="Recent">
      {#if recents.length}
        <ul class="list">
          {#each recents as p (p)}
            <li>
              <button onclick={() => openPath(p)} title={p} use:context={() => recentItems(p)}>
                <FileText size={16} />
                <span class="file">{p.split(/[\\/]/).pop()}</span>
                <span class="dir">{p.split(/[\\/]/).slice(0, -1).join("/")}</span>
              </button>
            </li>
          {/each}
        </ul>
      {:else}
        <p class="empty">None yet.</p>
      {/if}
    </Panel>
  </div>
  <div class="col wide">
    <Panel id="home.examples" icon={Shapes} title="Example designs">
      <ul class="list examples">
        {#each app.session?.examples ?? [] as ex (ex)}
          <li>
            <button onclick={() => openExample(ex)} use:context={() => [{ header: ex }, { label: "Open", run: () => openExample(ex) }, "-", ...pageItems()]}>
              <ComponentIcon type={iconFor(ex)} size={28} />
              <span class="file">{ex}</span>
            </button>
          </li>
        {/each}
      </ul>
    </Panel>
  </div>
</div>

<style>
  .home {
    flex: 1;
    overflow: auto;
    display: flex;
    gap: var(--gap);
    padding: var(--gap);
    align-items: flex-start;
  }
  .col {
    width: 380px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .col.wide {
    flex: 1;
    max-width: 720px;
  }
  .actions {
    display: flex;
    flex-direction: column;
    padding: 4px;
  }
  .act {
    display: flex;
    align-items: center;
    gap: 12px;
    border: 0;
    background: transparent;
    padding: 8px 10px;
    border-radius: var(--radius);
    text-align: left;
    color: var(--ink-2);
  }
  .act:hover {
    background: var(--surface-2);
    color: var(--ink);
  }
  .act span {
    display: flex;
    flex-direction: column;
  }
  .act b {
    color: var(--ink);
    font-weight: 600;
  }
  .act small {
    color: var(--ink-3);
    font-size: 12px;
  }
  .list {
    list-style: none;
    margin: 0;
    padding: 4px;
  }
  .list button {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 10px;
    border: 0;
    background: transparent;
    padding: 5px 8px;
    border-radius: var(--radius);
    text-align: left;
    color: var(--ink-2);
  }
  .list button:hover {
    background: var(--surface-2);
  }
  .file {
    color: var(--ink);
    white-space: nowrap;
  }
  .dir {
    font-size: 12px;
    color: var(--ink-3);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .examples {
    columns: 2 260px;
  }
  .examples li {
    break-inside: avoid;
  }
  .empty {
    margin: 0;
    padding: 10px 12px;
    color: var(--ink-3);
  }
</style>
