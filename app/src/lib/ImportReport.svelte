<script lang="ts">
  import Modal from "./Modal.svelte";
  import { app, save } from "./state.svelte";
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
  let own = $derived(r?.format === "OpenRocket");

  function copy() {
    if (!r) return;
    const text = [`${r.file} (${r.format})`, ...groups.flatMap((g) => ["", g.title + ":", ...g.items.map((f) => "- " + f.text)])].join("\n");
    copyText(text).then((ok) => ok && toast("Copied the import check"));
  }
</script>

{#if r && ui.importReport}
  <Modal title={own ? `Opened ${r.file}: things to check` : `Imported from ${r.format}: ${r.file}`} {close} width={720}>
    <div class="body">
      <p class="came">
        Came in: {r.cameIn.stages} stage{r.cameIn.stages === 1 ? "" : "s"}, {r.cameIn.parts} parts{#if r.cameIn.length}, {r.cameIn.length} long, {r.cameIn.mass}{/if},
        {r.cameIn.configurationsWithMotors ? `${r.cameIn.configurationsWithMotors} configuration${r.cameIn.configurationsWithMotors === 1 ? "" : "s"} with motors` : "no motors"},
        {r.cameIn.simulations ? `${r.cameIn.simulations} simulation${r.cameIn.simulations === 1 ? "" : "s"}` : "no simulations"}.
      </p>
      {#if !groups.length}
        <p class="ok">Nothing was left out and nothing looks wrong.</p>
      {/if}
      {#each groups as g (g.level)}
        <section class={g.level}>
          <h4>{g.title} <span class="n">{g.items.length}</span> <span class="sub">{g.sub}</span></h4>
          <ul>{#each g.items as f}<li>{f.text}</li>{/each}</ul>
        </section>
      {/each}
      {#if !own}
        <p class="foot">The original file is not changed. Save writes this design as an OpenRocket .ork, which keeps everything RocketMaxxing works with.</p>
      {/if}
      <div class="acts">
        <button class="btn" onclick={copy}>Copy</button>
        {#if !own}<button class="btn" onclick={() => (close(), save(true))}>Save as .ork…</button>{/if}
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
    font-size: 13px;
  }
  .n {
    display: inline-block;
    min-width: 18px;
    padding: 0 5px;
    border-radius: 999px;
    background: var(--surface-3);
    font-size: 11.5px;
    text-align: center;
  }
  .sub {
    font-weight: 400;
    color: var(--ink-3);
    font-size: 12px;
  }
  ul {
    margin: 0;
    padding-left: 18px;
    display: flex;
    flex-direction: column;
    gap: 4px;
    font-size: 13px;
  }
  .foot {
    font-size: 12px;
    color: var(--ink-3);
  }
  .acts {
    display: flex;
    justify-content: flex-end;
    gap: 6px;
  }
</style>
