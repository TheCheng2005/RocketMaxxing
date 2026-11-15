<script lang="ts">
  import Modal from "./Modal.svelte";
  import { app, save, projectOffers, run, refresh, checkRules, checkTargets } from "./state.svelte";
  import { call } from "./api";
  import { fd } from "./flightdata/store.svelte";
  import { applySettings, describe } from "./sharedSettings";
  import { copyText } from "./menu/menu.svelte";
  import { toast } from "./state.svelte";
  import { ui } from "./search/ui.svelte";

  // What to look at after a design file is opened: what was left out, what came in but may be wrong, what to set up
  // next. Shown once when the file is opened, and again from the "Imported" mark beside the design's name.
  let r = $derived(app.design?.importReport ?? null);
  const GROUPS = [
    { level: "lost", title: "Left out", sub: "In the file, not in the design here." },
    { level: "check", title: "Check these", sub: "Came in, but may not match the other program." },
    { level: "note", title: "To set up next", sub: "What this kind of file does not carry." },
  ] as const;
  let groups = $derived(GROUPS.map((g) => ({ ...g, items: (r?.findings ?? []).filter((f) => f.level === g.level) })).filter((g) => g.items.length));
  const close = () => (ui.importReport = false);
  let own = $derived(r?.format === "OpenRocket" || r?.format === "RocketMaxxing project");
  let project = $derived(app.design?.project ?? null);
  /** What the project offers that is not in use here, and what was taken up in this dialog. */
  let live = $derived(projectOffers(project));
  /** What it offered when the dialog opened: taken-up offers stay listed, marked, so the list does not move. */
  let shown = $state<string[] | null>(null);
  let done = $state<string[]>([]);
  $effect(() => {
    if (ui.importReport && shown === null) shown = live;
    if (!ui.importReport) {
      shown = null;
      done = [];
    }
  });
  let offers = $derived(shown ?? live);
  const name = (p: string) => p.split(/[\\/]/).pop();

  async function useStandards() {
    const ok = await run(() => call<{ saved: string; previous?: string }>("app_project_standards", { designId: app.design!.designId }), "Project");
    if (!ok) return;
    done = [...done, "standards"];
    toast(`Using the project's standards${ok.previous ? `; yours are kept as ${name(ok.previous)}` : ""}`);
    await refresh();
    checkRules();
    checkTargets();
  }
  function addPlots() {
    let saved: { name: string }[] = [];
    try {
      saved = JSON.parse(localStorage.getItem("rocketmaxxing.plots") ?? "[]");
    } catch {
      /* none yet */
    }
    const fresh = (project?.plots ?? []).filter((x) => !saved.some((y) => y.name === x.name));
    try {
      localStorage.setItem("rocketmaxxing.plots", JSON.stringify([...saved, ...fresh]));
    } catch {
      return void toast("The plots could not be kept here.", "error");
    }
    done = [...done, "plots"];
    toast(`Added ${fresh.length} saved plot${fresh.length === 1 ? "" : "s"}: ${fresh.map((x) => x.name).join(", ")}`);
  }
  async function useUnits() {
    await applySettings(project!.settings!);
    done = [...done, "settings"];
    toast(`Now reading numbers as the project does: ${describe(project!.settings!)}`);
  }
  function compareLog(path: string) {
    fd.path = path;
    close();
    app.page = "flightdata";
    toast("The project's flight log is chosen: press Compare to fly it against the simulation");
  }

  function copy() {
    if (!r) return;
    const text = [`${r.file} (${r.format})`, ...groups.flatMap((g) => ["", g.title + ":", ...g.items.map((f) => "- " + f.text)])].join("\n");
    copyText(text).then((ok) => ok && toast("Copied the import check"));
  }
</script>

{#if (r || offers.length) && ui.importReport}
  <Modal title={project ? `Opened the project ${name(app.design?.path ?? "") ?? ""}` : own ? `Opened ${r?.file}: things to check` : `Imported from ${r?.format}: ${r?.file}`} {close} width={720}>
    <div class="body">
      {#if project && offers.length}
        <section class="offer">
          <h4>It came with <span class="sub">Yours stay as they are unless you choose.</span></h4>
          <ul class="offers">
            {#if offers.includes("standards")}
              <li>
                <span>Its team standards: {project.standardsSummary}.</span>
                {#if done.includes("standards")}<span class="ok">In use</span>{:else}<button class="btn" onclick={useStandards}>Use them</button>{/if}
              </li>
            {/if}
            {#if offers.includes("settings") && project.settings}
              <li>
                <span>Its units and number format: {describe(project.settings)}.</span>
                {#if done.includes("settings")}<span class="ok">In use</span>{:else}<button class="btn" onclick={useUnits}>Use them</button>{/if}
              </li>
            {/if}
            {#if offers.includes("plots")}
              <li>
                <span>Saved plots: {(project.plots ?? []).map((x) => x.name).join(", ")}.</span>
                {#if done.includes("plots")}<span class="ok">Added</span>{:else}<button class="btn" onclick={addPlots}>Add them</button>{/if}
              </li>
            {/if}
            {#each project.flightLogs ?? [] as log (log)}
              <li><span>Flight log {name(log)}.</span><button class="btn" onclick={() => compareLog(log)}>Compare it</button></li>
            {/each}
          </ul>
        </section>
      {/if}
      {#if r}
      <p class="came">
        Came in: {r.cameIn.stages} stage{r.cameIn.stages === 1 ? "" : "s"}, {r.cameIn.parts} parts{#if r.cameIn.length}, {r.cameIn.length} long, {r.cameIn.mass}{/if},
        {r.cameIn.configurationsWithMotors ? `${r.cameIn.configurationsWithMotors} configuration${r.cameIn.configurationsWithMotors === 1 ? "" : "s"} with motors` : "no motors"},
        {r.cameIn.simulations ? `${r.cameIn.simulations} simulation${r.cameIn.simulations === 1 ? "" : "s"}` : "no simulations"}.
      </p>
      {#if !groups.length}
        <p class="ok">Nothing was left out and nothing looks wrong.</p>
      {/if}
      {/if}
      {#each r ? groups : [] as g (g.level)}
        <section class={g.level}>
          <h4>{g.title} <span class="n">{g.items.length}</span> <span class="sub">{g.sub}</span></h4>
          <ul>{#each g.items as f}<li>{f.text}</li>{/each}</ul>
        </section>
      {/each}
      {#if r && !own}
        <p class="foot">The original file is not changed. Save writes this design as an OpenRocket .ork, which keeps everything RocketMaxxing works with.</p>
      {/if}
      <div class="acts">
        {#if r}<button class="btn" onclick={copy}>Copy</button>{/if}
        {#if r && !own}<button class="btn" onclick={() => (close(), save(true))}>Save as .ork…</button>{/if}
        <button class="btn primary" onclick={close}>OK</button>
      </div>
    </div>
  </Modal>
{/if}

<style>
  .body {
    display: flex;
    flex-direction: column;
    gap: 12px;
    padding: 14px 16px;
    max-height: 70vh;
    overflow: auto;
  }
  p {
    margin: 0;
  }
  .came {
    color: var(--ink-2);
  }
  .ok {
    color: var(--good);
    font-weight: 600;
  }
  section {
    border-left: 3px solid var(--line-strong);
    padding-left: 12px;
  }
  section.lost {
    border-left-color: var(--bad);
  }
  section.check {
    border-left-color: var(--warn);
  }
  h4 {
    margin: 0 0 4px;
    font-size: calc(13px * var(--text));
  }
  .n {
    display: inline-block;
    min-width: 18px;
    padding: 0 5px;
    border-radius: 999px;
    background: var(--surface-3);
    font-size: calc(11.5px * var(--text));
    text-align: center;
  }
  .sub {
    font-weight: 400;
    color: var(--ink-3);
    font-size: calc(12px * var(--text));
  }
  ul {
    margin: 0;
    padding-left: 18px;
    display: flex;
    flex-direction: column;
    gap: 4px;
    font-size: calc(13px * var(--text));
  }
  .foot {
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
  }
  .offer {
    border-left: 3px solid var(--select);
    padding-left: 12px;
  }
  .offers {
    list-style: none;
    padding: 0;
    gap: 6px;
  }
  .offers li {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
  .offers .ok {
    font-size: calc(12.5px * var(--text));
    white-space: nowrap;
  }
  .acts {
    display: flex;
    justify-content: flex-end;
    gap: 6px;
  }
</style>
