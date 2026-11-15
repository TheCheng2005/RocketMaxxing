<script lang="ts">
  import { app } from "./state.svelte";

  let value = $state("");
  let input: HTMLInputElement | undefined = $state();

  $effect(() => {
    if (app.ask) {
      value = app.ask.value;
      queueMicrotask(() => input?.select());
    }
  });

  function done(ok: boolean) {
    const a = app.ask;
    app.ask = null;
    a?.resolve(ok && value.trim() ? value : null);
  }
</script>

{#if app.ask}
  <div class="scrim" role="presentation" onclick={(e) => e.target === e.currentTarget && done(false)}>
    <form class="dialog" onsubmit={(e) => (e.preventDefault(), done(true))}>
      <h3>{app.ask.title}</h3>
      <label>
        <span>{app.ask.label}</span>
        <input class="input" bind:this={input} bind:value onkeydown={(e) => e.key === "Escape" && done(false)} />
      </label>
      <div class="row">
        <button type="button" class="btn" onclick={() => done(false)}>Cancel</button>
        <button type="submit" class="btn primary">OK</button>
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
  .row {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 14px;
  }
</style>
