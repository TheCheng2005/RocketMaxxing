<script lang="ts">
  import { Download, X } from "@lucide/svelte";
  import { updates, skip, later } from "./updates.svelte";

  // Links open in the system's browser (the app's window hands every outside link to it).
  let u = $derived(updates.available);
</script>

{#if u}
  <div class="card" role="status">
    <div class="text">
      <b>RocketMaxxing {u.latest} is out</b>
      <span class="muted">You have {u.current}{u.prerelease ? ". It is a test version" : ""}. Install it over this one: designs and settings stay.</span>
    </div>
    <div class="acts">
      <a class="btn primary" href={u.download ?? u.url} target="_blank" rel="noopener noreferrer"><Download size={14} /> Download</a>
      {#if u.download && u.url}<a class="btn" href={u.url} target="_blank" rel="noopener noreferrer">What's new</a>{/if}
      <button class="btn" onclick={skip}>Skip this version</button>
    </div>
    <button class="close" onclick={later} aria-label="Later"><X size={14} /></button>
  </div>
{/if}

<style>
  .card {
    position: fixed;
    left: 88px;
    bottom: 16px;
    z-index: 30;
    display: flex;
    flex-direction: column;
    gap: 10px;
    max-width: 420px;
    padding: 14px 36px 14px 16px;
    border: 1px solid var(--line);
    border-radius: 10px;
    background: var(--surface);
    box-shadow: var(--shadow);
  }
  .text {
    display: flex;
    flex-direction: column;
    gap: 3px;
    font-size: 13px;
    line-height: 1.45;
  }
  .acts {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
  .acts a {
    text-decoration: none;
  }
  .close {
    position: absolute;
    top: 8px;
    right: 8px;
    border: 0;
    background: none;
    color: var(--ink-3);
    cursor: pointer;
    padding: 4px;
  }
</style>
