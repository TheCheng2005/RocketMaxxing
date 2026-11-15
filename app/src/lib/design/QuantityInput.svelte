<script lang="ts">
  import { choices, choose, editNum, pretty, readNum, toShown, toSi, unitOf, type Dim } from "../units.svelte";
  import { prefs } from "../prefs.svelte";

  // A number in the chosen unit, with the unit next to it (changing it changes that quantity's unit everywhere,
  // as in OpenRocket). Typing a unit ("3 in") also works; the engine reads it.
  let {
    value,
    dim,
    disabled = false,
    commit,
    id,
  }: { value: number | null; dim: Dim; disabled?: boolean; commit: (v: number | string) => void; id?: string } = $props();

  let unit = $derived(unitOf(dim));
  let shown = $derived(value === null || value === undefined ? "" : editNum(Math.abs(value) < 1e-12 ? 0 : toShown(value, dim, unit), 4).replace(/^-0$/, "0"));
  let text = $state("");
  let editing = $state(false);

  $effect(() => {
    if (!editing) text = shown;
  });

  // Up and down arrows step the number (Shift: ten times more, Alt: ten times less), sent a moment after the last.
  let sent = "";
  let nudgeTimer: ReturnType<typeof setTimeout> | undefined;
  function nudge(e: KeyboardEvent) {
    const plainText = readNum(text);
    const v = parseFloat(plainText);
    if (!Number.isFinite(v) || !/^[-+]?(\d+\.?\d*|\.\d+)$/.test(plainText)) return;
    e.preventDefault();
    let step = v === 0 ? 1 : Math.pow(10, Math.floor(Math.log10(Math.abs(v))) - 1);
    if (e.shiftKey) step *= 10;
    if (e.altKey) step /= 10;
    const digits = Math.max(0, -Math.floor(Math.log10(step) + 1e-9));
    const next = v + (e.key === "ArrowUp" ? step : -step);
    const decimals = Math.max(digits, (plainText.split(".")[1] ?? "").length);
    const stepped = (Math.abs(next) < step / 1000 ? 0 : next).toFixed(decimals);
    text = prefs.mark === "comma" ? stepped.replace(".", ",") : stepped;
    editing = true;
    clearTimeout(nudgeTimer);
    nudgeTimer = setTimeout(() => {
      sent = readNum(text);
      commit(toSi(parseFloat(readNum(text)), dim, unit));
    }, 350);
  }

  function done() {
    editing = false;
    clearTimeout(nudgeTimer);
    // The chosen decimal mark, and a stray thousands separator, are read here; a unit typed after it goes to the engine.
    const t = readNum(text);
    if (t === sent) {
      sent = "";
      return;
    }
    if (t === readNum(shown) || t === "") {
      text = shown;
      return;
    }
    if (/^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/.test(t)) {
      commit(toSi(parseFloat(t), dim, unit));
    } else {
      commit(t); // with a unit, or an expression the engine explains if it cannot read it
    }
  }
</script>

<div class="q" class:disabled>
  <input
    {id}
    class="input num"
    title="↑ ↓ step it (Shift: ×10, Alt: ÷10). A unit can be typed too: 3 in, 75 mm."
    {disabled}
    bind:value={text}
    onfocus={() => (editing = true)}
    onblur={done}
    onkeydown={(e) => {
      if (e.key === "Enter") (e.target as HTMLInputElement).blur();
      if (e.key === "ArrowUp" || e.key === "ArrowDown") nudge(e);
      if (e.key === "Escape") {
        text = shown;
        editing = false;
        (e.target as HTMLInputElement).blur();
      }
    }}
  />
  <select class="unit" value={unit.unit} onchange={(e) => choose(dim, (e.target as HTMLSelectElement).value)} tabindex="-1">
    {#each choices(dim) as u (u.unit)}
      <option value={u.unit}>{pretty(u.unit)}</option>
    {/each}
  </select>
</div>

<style>
  .q {
    display: flex;
    min-width: 0;
  }
  .input {
    flex: 1;
    min-width: 0;
    width: 100%;
    border-top-right-radius: 0;
    border-bottom-right-radius: 0;
    text-align: right;
  }
  .unit {
    flex: none;
    width: 62px;
    height: var(--control);
    border: 1px solid var(--line);
    border-left: 0;
    border-radius: 0 var(--radius) var(--radius) 0;
    background: var(--surface-2);
    color: var(--ink-2);
    font-size: calc(12.5px * var(--text));
    padding: 0 4px;
  }
  .disabled .input {
    background: var(--surface-2);
    color: var(--ink-3);
  }
</style>
