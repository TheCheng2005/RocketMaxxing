<script lang="ts">
  import { Scaling } from "@lucide/svelte";
  import { app, act, findNode, toast } from "../state.svelte";
  import Modal from "../Modal.svelte";

  // Scale the rocket or one part (with what is inside it), as OpenRocket's Edit > Scale: by a factor, or to a body
  // diameter or overall length. One undo puts it back.
  let { part = "rocket", close }: { part?: string; close: () => void } = $props();

  let name = $derived(part === "rocket" ? app.design?.name ?? "the rocket" : findNode(app.design?.tree, part)?.name ?? "the part");
  type Mode = "factor" | "diameter" | "length";
  let mode = $state<Mode>("factor");
  let value = $state("2");
  let thickness = $state(true);
  let offsets = $state(true);
  let masses = $state(false);
  let busy = $state(false);
  const HINT: Record<Mode, string> = {
    factor: "2 doubles every length, 0.5 halves it",
    diameter: "The largest body tube's outside diameter, with its unit (e.g. 98 mm or 4 in)",
    length: "Nose tip to tail, with its unit (e.g. 2.4 m or 96 in)",
  };

  async function apply() {
    const v = value.trim();
    if (!v) return;
    busy = true;
    try {
      const args: Record<string, unknown> = { thickness, offsets, masses, [mode]: mode === "factor" ? Number(v.replace(",", ".")) : v };
      if (part !== "rocket") args.components = [part];
      const r = await act("scale_design", args);
      if (r) {
        toast(`Scaled ${name} by ${r.factor}: ${r.length}. Undo puts it back.`);
        close();
      }
    } finally {
      busy = false;
    }
  }
</script>

<Modal title="Scale {name}" {close} width={520}>
  <form class="f" onsubmit={(e) => (e.preventDefault(), apply())}>
    <div class="seg" role="group" aria-label="Scale by">
      <button type="button" class:on={mode === "factor"} onclick={() => ((mode = "factor"), (value = "2"))}>By a factor</button>
      <button type="button" class:on={mode === "diameter"} disabled={part !== "rocket"} onclick={() => ((mode = "diameter"), (value = ""))}>To a diameter</button>
      <button type="button" class:on={mode === "length"} disabled={part !== "rocket"} onclick={() => ((mode = "length"), (value = ""))}>To a length</button>
    </div>
    <label class="v">
      <span>{mode === "factor" ? "Factor" : mode === "diameter" ? "Body diameter" : "Overall length"}</span>
      <!-- svelte-ignore a11y_autofocus -->
      <input class="input" bind:value autofocus placeholder={HINT[mode]} />
      <small class="muted">{HINT[mode]}</small>
    </label>
    <label class="c"><input type="checkbox" bind:checked={thickness} /> Scale wall thicknesses <small class="muted">off: keep the same tubing and fin stock</small></label>
    <label class="c"><input type="checkbox" bind:checked={offsets} /> Scale positions <small class="muted">where parts sit inside their parents</small></label>
    <label class="c"><input type="checkbox" bind:checked={masses} /> Scale fixed masses <small class="muted">by the factor cubed; off keeps weighed masses</small></label>
    <p class="muted note">Motors stay as they are: check they still fit the scaled mount.</p>
    <div class="acts">
      <button type="button" class="btn" onclick={close}>Cancel</button>
      <button class="btn primary" disabled={busy || !value.trim()}><Scaling size={14} /> Scale</button>
    </div>
  </form>
</Modal>

<style>
  .f {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .seg {
    display: flex;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    overflow: hidden;
    align-self: flex-start;
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
  .seg button:disabled {
    opacity: 0.45;
  }
  .v {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .v span {
    font-size: calc(12.5px * var(--text));
    color: var(--ink-2);
  }
  .c {
    display: flex;
    align-items: baseline;
    gap: 8px;
  }
  small {
    font-size: calc(11.5px * var(--text));
  }
  .note {
    margin: 0;
    font-size: calc(12px * var(--text));
  }
  .acts {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
  }
</style>
