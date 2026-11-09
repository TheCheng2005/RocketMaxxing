<script lang="ts">
  import { Clapperboard, FolderOpen, Play, CircleCheck } from "@lucide/svelte";
  import { call } from "../api";
  import { app, run, toast } from "../state.svelte";
  import Modal from "../Modal.svelte";
  import RocketLoader from "../RocketLoader.svelte";
  import { pathOf, sizeOf } from "./media";

  // The simulated flight as a video: a chase camera on the rocket, the separations and canopies, the numbers on screen.
  // Saved as an MP4 in the Downloads folder (else the Desktop), to play, share or put in slides; nothing plays in here.
  let { simulation, name, close }: { simulation: number; name: string; close: () => void } = $props();

  let duration = $state(25);
  let stills = $state(true);
  let gif = $state(false);
  let busy = $state(false);
  let result = $state<any>(null);
  let folder = $state("");

  async function make() {
    busy = true;
    result = null;
    try {
      const where = await call<{ path: string; folderName: string }>("app_download_path", {
        name: `${app.design?.name ?? "Rocket"} flight.mp4`,
      });
      folder = where.folderName;
      result = await run(() =>
        call("animate_flight", {
          designId: app.design!.designId,
          simulation: String(simulation),
          duration,
          stills,
          gif,
          path: where.path,
        }),
      );
    } finally {
      busy = false;
    }
  }

  // "mp4" is the file and its size, or a note saying why there is none.
  let video = $derived(/\.mp4\s*\(/.test(result?.mp4 ?? "") ? pathOf(result.mp4) : null);
  let gifFile = $derived(pathOf(result?.gif));
  let sheet = $derived(pathOf(result?.stills));
  const fileName = (p: string) => p.split(/[\\/]/).pop() ?? p;

  function reveal(path: string, open = false) {
    call("app_reveal", { path, open }).catch((e) => toast(String(e?.message ?? e)));
  }
</script>

<Modal title="Flight video: {name}" {close} width={720}>
  <div class="form">
    <label><span>Length (s)</span><input class="input" type="number" min="10" max="120" bind:value={duration} /></label>
    <label class="check"><input type="checkbox" bind:checked={stills} /> Key-moment stills (PNG)</label>
    <label class="check"><input type="checkbox" bind:checked={gif} /> Also a GIF (for chat)</label>
    <button class="btn primary" disabled={busy} onclick={make}><Clapperboard size={15} /> {busy ? "Making the video…" : "Make video"}</button>
  </div>
  <p class="muted note">
    Saved as an MP4 in your Downloads folder. The flight plays in real time through the burn, apogee and each deployment;
    the coast and the descent are sped up to fit the length.
  </p>

  {#if busy}<RocketLoader text="Simulating the flight and drawing every frame: about a minute for a 25 s video." />{/if}
  {#if result}
    {#if video}
      <div class="saved">
        <CircleCheck size={20} />
        <div class="what">
          <b>Saved to {folder}</b>
          <span>{fileName(video)} <span class="muted">· {sizeOf(result.mp4)} · {result.length}</span></span>
        </div>
        <button class="btn primary" onclick={() => reveal(video!, true)}><Play size={14} /> Open video</button>
        <button class="btn" onclick={() => reveal(video!)}><FolderOpen size={14} /> Show in folder</button>
      </div>
      {#if gifFile || sheet}
        <p class="muted note">
          Beside it: {[gifFile && `${fileName(gifFile)} (${sizeOf(result.gif)})`, sheet && fileName(sheet)].filter(Boolean).join(", ")}.
        </p>
      {/if}
    {:else}
      <p class="note warn">No MP4 was made: {result.mp4 ?? "the engine gave no reason"}</p>
    {/if}
    {#if result.timeline?.length}
      <table class="grid">
        <thead><tr><th>Event</th><th class="n">Flight time</th><th class="n">In the video</th><th></th></tr></thead>
        <tbody>
          {#each result.timeline as t, i (i)}
            <tr><td>{t.event}</td><td class="n">{t.flightTime}</td><td class="n">{t.videoTime}</td><td class="muted">{t.detail ?? ""}</td></tr>
          {/each}
        </tbody>
      </table>
    {/if}
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
    font-size: 12px;
    color: var(--ink-2);
  }
  .form label.check {
    flex-direction: row;
    align-items: center;
    gap: 5px;
    height: 30px;
  }
  .form .input {
    width: 80px;
  }
  .note {
    font-size: 12px;
    margin: 8px 0;
  }
  .warn {
    color: var(--warn);
  }
  .saved {
    display: flex;
    align-items: center;
    gap: 12px;
    margin: 12px 0;
    padding: 12px 14px;
    border-radius: 8px;
    background: var(--good-soft);
  }
  .saved :global(svg:first-child) {
    flex: none;
    color: var(--good);
  }
  .what {
    display: flex;
    flex-direction: column;
    gap: 2px;
    flex: 1;
    min-width: 0;
    font-size: 13px;
  }
  .what > span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .n {
    text-align: right;
    white-space: nowrap;
  }
  th {
    text-align: left;
    font-size: 12px;
    color: var(--ink-3);
  }
</style>
