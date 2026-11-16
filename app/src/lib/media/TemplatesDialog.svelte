<script lang="ts">
  import { Printer, FolderOpen, FileText } from "@lucide/svelte";
  import { call } from "../api";
  import { app, run } from "../state.svelte";
  import { units } from "../units.svelte";
  import Modal from "../Modal.svelte";
  import RocketLoader from "../RocketLoader.svelte";
  import { exportPath } from "./media";

  // Full-size templates to print and cut, as OpenRocket prints them: fins, centering rings and bulkheads, transition
  // shrouds, the nose cone profile and the fin marking guide. One PDF in the designs folder's exports.
  let { close }: { close: () => void } = $props();

  const KINDS: [string, string, string][] = [
    ["fins", "Fins", "Each fin set's outline with its tab"],
    ["rings", "Rings and bulkheads", "Centering rings with the motor tube holes, bulkheads; identical ones once"],
    ["transitions", "Transition shrouds", "The flat pattern to roll a paper or card transition"],
    ["nose", "Nose cone profile", "Half the profile at full size, to check a turned or printed nose"],
    ["marking", "Fin marking guide", "A strip to wrap round each body tube with the fin and rail button lines"],
  ];
  const PAPERS = ["A4", "Letter", "A3", "Tabloid"];
  let paper = $state(units.system === "imperial" ? "Letter" : "A4");
  let kinds = $state(KINDS.map(([k]) => k));
  let busy = $state(false);
  let result = $state<any>(null);

  async function make() {
    busy = true;
    result = null;
    try {
      result = await run(() => call("print_templates", { designId: app.design!.designId, paper, include: kinds, path: exportPath("templates.pdf") }), "Templates");
    } finally {
      busy = false;
    }
  }
  const open = () => run(() => call("app_reveal", { path: result.written, open: true }), "Templates");
  const show = () => run(() => call("app_reveal", { path: result.written }), "Templates");
  const NAMES: Record<string, string> = { fins: "Fins", rings: "Ring", transitions: "Shroud", nose: "Nose profile", marking: "Marking guide" };
</script>

<Modal title="Print templates for {app.design?.name ?? 'the design'}" {close} width={760}>
  <p class="muted lead">Full-size templates to print and cut. Each page has a 100 mm and a 4 in bar: print at 100% (actual size, not “fit to page”) and measure one before cutting.</p>
  <div class="kinds">
    {#each KINDS as [k, label, hint] (k)}
      <label title={hint}>
        <input type="checkbox" checked={kinds.includes(k)} onchange={(e) => (kinds = (e.target as HTMLInputElement).checked ? [...kinds, k] : kinds.filter((x) => x !== k))} />
        <span><b>{label}</b><small>{hint}</small></span>
      </label>
    {/each}
  </div>
  <div class="row">
    <span class="lbl">Paper</span>
    <div class="seg" role="group" aria-label="Paper size">
      {#each PAPERS as p (p)}<button class:on={paper === p} onclick={() => (paper = p)}>{p}</button>{/each}
    </div>
    <span class="grow"></span>
    <button class="btn primary" disabled={busy || !kinds.length} onclick={make}><FileText size={14} /> Make the PDF</button>
  </div>
  {#if busy}
    <RocketLoader text="Laying out the templates" />
  {:else if result}
    <div class="done">
      <p><b>{result.pages} page{result.pages === 1 ? "" : "s"}</b> of {result.paper}, in <span class="path">{result.written}</span></p>
      <div class="acts">
        <button class="btn primary" onclick={open}><Printer size={14} /> Open to print</button>
        <button class="btn" onclick={show}><FolderOpen size={14} /> Show in folder</button>
      </div>
    </div>
    <table class="grid">
      <thead><tr><th>Template</th><th>Part</th><th class="n">Size</th><th class="n">Page</th></tr></thead>
      <tbody>
        {#each result.templates as t, i (i)}
          <tr>
            <td class="muted">{NAMES[t.template] ?? t.template}</td>
            <td>{t.part}{#if t.pieces}<small class="muted"> · in {t.pieces} pieces</small>{/if}</td>
            <td class="n">{t.size}</td>
            <td class="n">{t.page}</td>
          </tr>
        {/each}
      </tbody>
    </table>
    {#if result.notDrawn?.length}<p class="muted">Not drawn: {result.notDrawn.join("; ")}</p>{/if}
  {/if}
</Modal>

<style>
  .lead {
    margin: 0 0 12px;
  }
  .kinds {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(calc(220px * var(--text)), 1fr));
    gap: 8px;
  }
  .kinds label {
    display: flex;
    gap: 8px;
    align-items: flex-start;
    padding: 8px 10px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    cursor: pointer;
  }
  .kinds span {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .kinds small {
    color: var(--ink-3);
    font-size: calc(11.5px * var(--text));
  }
  .row {
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 14px 0 10px;
    flex-wrap: wrap;
  }
  .lbl {
    color: var(--ink-2);
    font-size: calc(12.5px * var(--text));
  }
  .grow {
    flex: 1;
  }
  .seg {
    display: flex;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    overflow: hidden;
  }
  .seg button {
    border: 0;
    background: var(--surface);
    padding: 6px 10px;
    color: var(--ink-2);
    font-size: calc(12.5px * var(--text));
  }
  .seg button.on {
    background: var(--select);
    color: #fff;
  }
  .done {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    flex-wrap: wrap;
    padding: 10px 12px;
    border-radius: var(--radius);
    background: var(--select-soft);
  }
  .done p {
    margin: 0;
  }
  .acts {
    display: flex;
    gap: 8px;
  }
  .path {
    word-break: break-all;
    font-size: calc(12px * var(--text));
    color: var(--ink-2);
  }
  .grid {
    margin-top: 10px;
    width: 100%;
  }
  .n {
    text-align: right;
    white-space: nowrap;
    width: 1%;
  }
</style>
