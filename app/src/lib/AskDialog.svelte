<script lang="ts">
  import { app } from "./state.svelte";

  let value = $state("");
  let input: HTMLInputElement | undefined = $state();
  let cancel: HTMLButtonElement | undefined = $state();

  $effect(() => {
    if (app.ask) {
      value = app.ask.value;
      // a question with no box: Cancel has the focus, so Enter does nothing that cannot be undone
      queueMicrotask(() => (app.ask?.confirm ? cancel?.focus() : input?.select()));
    }
  });

  function done(ok: boolean) {
    const a = app.ask;
    app.ask = null;
    a?.resolve(ok && (a.confirm || value.trim()) ? a.confirm ? "yes" : value : null);
  }
</script>

<svelte:window onkeydown={(e) => app.ask && e.key === "Escape" && done(false)} />

{#if app.ask}
  <div class="scrim" role="presentation" onclick={(e) => e.target === e.currentTarget && done(false)}>
    <form class="dialog" onsubmit={(e) => (e.preventDefault(), done(true))}>
      <h3>{app.ask.title}</h3>
      {#if app.ask.confirm}
        <p class="text">{app.ask.label}</p>
      {:else}
        <label>
          <span>{app.ask.label}</span>
          <input class="input" bind:this={input} bind:value />
        </label>
      {/if}
      <div class="row">
        <button type="button" class="btn" bind:this={cancel} onclick={() => done(false)}>Cancel</button>
        <button type="submit" class="btn" class:primary={!app.ask.confirm} class:danger={!!app.ask.confirm}>{app.ask.confirm ?? "OK"}</button>
      </div>
    </form>
  </div>
{/if}

<style>
  .scrim {
    position: fixed;
    inset: 0;
    background: rgb(0 0 0 / 30%);
    display: grid;
    place-items: center;
    z-index: 40;
  }
  .dialog {
    width: min(460px, calc(100vw - 32px));
    padding: 14px 16px;
    background: var(--surface);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius);
    box-shadow: var(--shadow-pop);
  }
  h3 {
    margin: 0 0 10px;
    font-size: calc(14px * var(--text));
  }
  label {
    display: flex;
    flex-direction: column;
    gap: 6px;
    color: var(--ink-2);
    font-size: calc(13px * var(--text));
  }
  .input {
    height: 30px;
  }
  .text {
    margin: 0;
    color: var(--ink-2);
    font-size: calc(13px * var(--text));
    line-height: 1.45;
  }
  .danger {
    background: var(--bad, #c0392b);
    border-color: var(--bad, #c0392b);
    color: var(--surface);
  }
  .row {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 14px;
  }
</style>
