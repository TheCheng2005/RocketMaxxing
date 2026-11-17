<script lang="ts">
  import { untrack } from "svelte";
  import { Image, FolderOpen } from "@lucide/svelte";
  import { call, fileUrl } from "../api";
  import { app, run } from "../state.svelte";
  import Modal from "../Modal.svelte";
  import RocketLoader from "../RocketLoader.svelte";
  import { exportPath } from "./media";
  // The photo studio (with its post-processing) loads only when it is chosen.
  const PhotoStudio = () => import("./PhotoStudio.svelte");

  // A picture of the design for reviews and build notes: shaded 3-D (exploded, cut-away or outside, with a numbered parts
  // list), or OpenRocket's side drawing with CG and CP. Written to the designs folder.
  let { close }: { close: () => void } = $props();

  type Kind = "exploded" | "cutaway" | "assembled" | "drawing" | "photo";
  const KINDS: [Kind, string][] = [
    ["exploded", "Exploded, with parts list"],
    ["cutaway", "Cut-away"],
    ["assembled", "Outside"],
    ["drawing", "Side drawing (SVG)"],
    ["photo", "Photo studio"],
  ];
  let kind = $state<Kind>("exploded");
  let azimuth = $state(25);
  let elevation = $state(22);
  let width = $state(1600);
  let busy = $state(false);
  let result = $state<any>(null);

  async function make() {
    if (kind === "photo") return; // drawn in the page by the photo studio
    busy = true;
    try {
      const designId = app.design!.designId;
      result =
        kind === "drawing"
          ? await run(() => call("draw_rocket", { designId, path: exportPath("drawing.svg") }))
          : await run(() => call("render_3d", { designId, view: kind, azimuth, elevation, width, path: exportPath(`${kind}.png`) }));
    } finally {
      busy = false;
    }
  }

  $effect(() => {
    kind;
    untrack(make);
  });
</script>

<Modal title="Picture of {app.design?.name ?? 'the design'}" {close} width={1000}>
  <div class="form">
    <div class="seg">
      {#each KINDS as [k, label] (k)}<button class:on={kind === k} onclick={() => (kind = k)}>{label}</button>{/each}
    </div>
    {#if kind !== "drawing" && kind !== "photo"}
      <label><span>Turn (°)</span><input class="input" type="number" min="-90" max="90" bind:value={azimuth} onchange={make} /></label>
      <label><span>Look down (°)</span><input class="input" type="number" min="-60" max="80" bind:value={elevation} onchange={make} /></label>
      <label>
        <span>Width</span>
        <select class="select" bind:value={width} onchange={make}>
          <option value={1200}>1200 px</option><option value={1600}>1600 px</option><option value={2400}>2400 px</option><option value={3200}>3200 px</option>
        </select>
      </label>
    {/if}
    {#if result?.file && kind !== "photo"}
      <button class="btn" onclick={() => call("app_reveal", { path: result.file }).catch(() => {})}><FolderOpen size={14} /> Show in folder</button>
    {/if}
  </div>
  {#if kind === "photo"}
    <div class="view">{#await PhotoStudio() then m}<m.default />{/await}</div>
  {:else}
  <div class="view" class:stale={busy}>
    {#if result?.file}
      <img src={fileUrl(result.file)} alt="The design" />
      <p class="muted path"><Image size={13} /> {result.file}</p>
    {:else if busy}
      <RocketLoader text="Drawing the picture" />
    {/if}
  </div>
  {/if}
  {#if kind !== "photo" && result?.parts?.length}
    <table class="grid">
      <thead><tr><th class="n">#</th><th>Part</th><th>Kind</th><th class="n">Mass</th></tr></thead>
      <tbody>
        {#each result.parts as p (p.n)}<tr><td class="n">{p.n}</td><td>{p.name}</td><td class="muted">{p.kind}</td><td class="n">{p.mass}</td></tr>{/each}
      </tbody>
    </table>
  {/if}
</Modal>

<style>
  .form {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-end;
    gap: 10px 14px;
  }
  .form label {
    display: flex;
    flex-direction: column;
    gap: 3px;
    font-size: calc(12px * var(--text));
    color: var(--ink-2);
  }
  .form .input {
    width: 72px;
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
  .view {
    margin-top: 10px;
    min-height: 120px;
  }
  .view.stale {
    opacity: 0.6;
  }
  img {
    display: block;
    width: 100%;
    background: #fff;
    border: 1px solid var(--line);
    border-radius: var(--radius);
  }
  .path {
    display: flex;
    align-items: center;
    gap: 5px;
    font-size: calc(12px * var(--text));
    margin: 4px 0 8px;
    word-break: break-all;
  }
  .n {
    text-align: right;
    white-space: nowrap;
    width: 1%;
  }
  th {
    text-align: left;
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
  }
</style>
