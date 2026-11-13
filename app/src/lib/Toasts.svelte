<script lang="ts">
  import { app } from "./state.svelte";
  import { KINDS, openIssues } from "./issues/issues.svelte";
</script>

<div class="toasts" aria-live="polite">
  {#each app.toasts as t (t.id)}
    <div class="toast {t.kind} {t.issue ? 'k-' + t.issue.kind : ''}">
      {#if t.issue}<b class="tag">{KINDS[t.issue.kind].label}</b>{/if}
      <span class="text">
        {t.text}
        {#if t.issue && (t.issue.fix || t.issue.causes[0])}<span class="why">{t.issue.fix ?? "Likely cause: " + t.issue.causes[0]}</span>{/if}
      </span>
      {#if t.issue}
        <button class="more" onclick={() => { openIssues(); app.toasts = app.toasts.filter((x) => x.id !== t.id); }}>Details</button>
      {/if}
    </div>
  {/each}
</div>

<style>
  .toasts {
    position: fixed;
    right: 16px;
    bottom: 16px;
    display: flex;
    flex-direction: column;
    gap: 8px;
    z-index: 50;
    max-width: 460px;
  }
  .toast {
    display: flex;
    flex: none;
    align-items: baseline;
    gap: 8px;
    padding: 7px 12px;
    border-radius: var(--radius);
    background: var(--ink);
    color: var(--surface);
    box-shadow: var(--shadow-pop);
    font-size: 13px;
  }
  .toast.error {
    background: var(--bad);
    color: #fff;
  }
  .toast.error.k-input {
    background: var(--ink);
    color: var(--surface);
  }
  .text {
    flex: 1;
    min-width: 0;
  }
  .why {
    display: block;
    margin-top: 3px;
    font-size: 12px;
    opacity: 0.85;
  }
  .tag {
    flex: none;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    opacity: 0.85;
  }
  .more {
    flex: none;
    border: 0;
    background: transparent;
    color: inherit;
    text-decoration: underline;
    padding: 0;
    font-size: 12px;
  }
</style>
