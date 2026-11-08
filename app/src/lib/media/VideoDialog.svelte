<script lang="ts">
  import { Clapperboard, FolderOpen } from "@lucide/svelte";
  import { call, fileUrl } from "../api";
  import { app, run } from "../state.svelte";
  import Modal from "../Modal.svelte";
  import RocketLoader from "../RocketLoader.svelte";
  import { exportPath, pathOf, sizeOf } from "./media";

  // The simulated flight as a video: a chase camera on the rocket, the separations and canopies, the numbers on screen.
  // Written to the designs folder (GIF, MP4 when ffmpeg is installed, and a sheet of key-moment stills).
  let { simulation, name, close }: { simulation: number; name: string; close: () => void } = $props();

  let duration = $state(25);
  let gifWidth = $state(720);
  let mp4 = $state(true);
  let stills = $state(true);
  let busy = $state(false);
  let result = $state<any>(null);

  async function make() {
    busy = true;
    result = null;
    try {
      result = await run(() =>
        call("animate_flight", {
          designId: app.design!.designId,
          simulation: String(simulation),
          duration,
          gifWidth,
          mp4,
          stills,
          path: exportPath("flight.gif"),
        }),
      );
    } finally {
      busy = false;
    }
  }

  let gif = $derived(pathOf(result?.gif));
  // "mp4" is the file and its size, or (without ffmpeg) a note saying why there is none.
  let video = $derived(/\.mp4\s*\(/.test(result?.mp4 ?? "") ? pathOf(result.mp4) : null);
  // RocketMaxxing's window (Chromium without the H.264 licence) cannot play the MP4: it shows the GIF instead.
  const playsMp4 = document.createElement("video").canPlayType('video/mp4; codecs="avc1.640028"') !== "";
  let mp4Note = $derived(result?.mp4 && !video ? String(result.mp4) : "");
  let sheet = $derived(pathOf(result?.stills));
</script>

<Modal title="Flight video: {name}" {close} width={900}>
  <div class="form">
    <label><span>Length (s)</span><input class="input" type="number" min="10" max="120" bind:value={duration} /></label>
    <label>
      <span>GIF size</span>
      <select class="select" bind:value={gifWidth}>
        <option value={480}>Small (480 px)</option>
        <option value={720}>Medium (720 px)</option>
        <option value={960}>Large (960 px)</option>
      </select>
    </label>
    <label class="check"><input type="checkbox" bind:checked={mp4} /> MP4 too (needs ffmpeg)</label>
    <label class="check"><input type="checkbox" bind:checked={stills} /> Key-moment stills</label>
    <button class="btn primary" disabled={busy} onclick={make}><Clapperboard size={15} /> {busy ? "Making the video…" : "Make video"}</button>
  </div>
  <p class="muted note">The flight is played in real time through the burn, apogee and each deployment; the coast and the descent are sped up to fit the length.</p>

  {#if busy}<RocketLoader text="Simulating the flight and drawing every frame: about a minute for a long video." />{/if}
  {#if result}
    {#if video && (playsMp4 || !gif)}
      <!-- svelte-ignore a11y_media_has_caption -->
      <video src={fileUrl(video)} controls autoplay loop muted></video>
    {:else if gif}
      <img src={fileUrl(gif)} alt="Flight video" />
    {/if}
    <div class="files">
      {#if gif}<span>GIF {sizeOf(result.gif)}</span>{/if}
      {#if video}<span>MP4 {sizeOf(result.mp4)}</span>{/if}
      <span class="muted">{result.length}</span>
      {#if gif}<button class="btn" onclick={() => call("app_reveal", { path: gif }).catch(() => {})}><FolderOpen size={14} /> Show in folder</button>{/if}
    </div>
    {#if mp4Note}<p class="muted note">{mp4Note}</p>{/if}
    {#if sheet}<img class="sheet" src={fileUrl(sheet)} alt="Key moments" />{/if}
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
  video,
  img {
    display: block;
    width: 100%;
    border-radius: var(--radius);
    background: #000;
    margin-top: 8px;
  }
  img.sheet {
    background: transparent;
    border: 1px solid var(--line);
  }
  .files {
    display: flex;
    align-items: center;
    gap: 12px;
    margin: 8px 0;
    font-size: 12.5px;
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
