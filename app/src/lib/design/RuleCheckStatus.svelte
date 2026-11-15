<script lang="ts">
  import { RefreshCw } from "@lucide/svelte";
  import { app, checkRules } from "../state.svelte";
  import { status } from "../analysis/text";
  import RocketLoader from "../RocketLoader.svelte";

  // Counts for the rule check panel's title bar, shown even when the panel is folded.
  let c = $derived(app.checks && app.checks.designId === app.design?.designId ? app.checks : null);
  let counts = $derived.by(() => {
    const n = { fail: 0, warn: 0, pass: 0 };
    for (const x of c?.checks ?? []) {
      const s = status(x.status);
      if (s) n[s]++;
    }
    return n;
  });
</script>

<span class="counts">
  {#if app.checking}<span title="Checking"><RocketLoader size="tiny" /></span>{/if}
  {#if c?.checks}
    <span class:bad={counts.fail > 0}>{counts.fail} fail</span>
    <span class:warn={counts.warn > 0}>{counts.warn} warn</span>
    <span>{counts.pass} pass</span>
  {:else if c?.error}
    <span>not checked</span>
  {/if}
</span>
<button class="tool" title="Check again" onclick={() => checkRules()}><RefreshCw size={14} /></button>

<style>
  .counts {
    display: flex;
    gap: 8px;
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
    white-space: nowrap;
  }
  .bad {
    color: var(--bad);
    font-weight: 600;
  }
  .warn {
    color: var(--warn);
    font-weight: 600;
  }
  .tool {
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    border: 0;
    border-radius: 3px;
    background: transparent;
    color: var(--ink-2);
  }
  .tool:hover {
    background: var(--surface-3);
  }
</style>
