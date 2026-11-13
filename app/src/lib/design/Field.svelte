<script lang="ts">
  import { app, editField, type FieldDef } from "../state.svelte";
  import { fmt, num, type Dim } from "../units.svelte";
  import QuantityInput from "./QuantityInput.svelte";
  import { context, copyText, type MenuItem } from "../menu/menu.svelte";
  import { toast } from "../state.svelte";

  let { f, more }: { f: FieldDef; more?: () => MenuItem[] } = $props();

  // Right-click on a setting: its choices (a list or a box to tick), Automatic, copy the value; then the panel's.
  function items(): MenuItem[] {
    const text = f.kind === "quantity" ? fmt(f.value, f.dim as Dim) : f.kind === "enum" ? (f.options?.find((o) => o.value === f.value)?.label ?? String(f.value)) : String(f.value ?? "");
    return [
      { header: f.label },
      ...(f.kind === "enum" ? (f.options ?? []).map((o) => ({ label: o.label, checked: o.value === f.value, off: off && "Not used now", run: () => editField(f.key, o.value) })) : []),
      f.kind === "bool" ? { label: f.label, checked: !!f.value, off: off && "Not used now", run: () => editField(f.key, !f.value) } : null,
      f.auto ? { label: "Automatic (from the parts next to it)", checked: !!f.isAuto, off: off && "Not used now", run: () => editField(f.auto!, !f.isAuto) } : null,
      f.kind !== "bool" ? { label: "Copy value", off: !text && "Empty", run: () => copyText(text).then((ok) => ok && toast(`Copied ${text}`)) } : null,
      "-",
      ...(more?.() ?? []),
    ].filter(Boolean) as MenuItem[];
  }

  let off = $derived(f.enabled === false);
  let text = $state("");
  $effect(() => {
    text = f.value == null ? "" : String(f.value);
  });
  // Plain numbers without thousands separators, so they read back as numbers.
  let plain = $derived(f.value == null ? "" : num(f.value, 4).replace(/,/g, ""));

  let materials = $derived(app.session?.materials?.[f.materialType ?? "BULK"] ?? []);

  /** The engine refused the last value typed here: said under the field, where it happened. */
  let bad = $derived(app.fieldError && app.fieldError.component === app.selected && app.fieldError.key === f.key ? app.fieldError : null);

  /** What the field showed before a value that was refused. */
  function restore(el?: HTMLInputElement) {
    text = f.value == null ? "" : String(f.value);
    if (el) el.value = f.kind === "number" ? plain : text;
  }

  async function commitText() {
    if (text !== String(f.value ?? "") && (await editField(f.key, text)) === undefined) restore();
  }

  async function commitNumber(t: string, el?: HTMLInputElement) {
    t = t.trim().replace(/,/g, "");
    if (t === "" || t === plain || Number(t) === f.value) return restore(el);
    const v = Number(t);
    if ((await editField(f.key, Number.isFinite(v) ? v : t)) === undefined) restore(el);
  }

  function enter(e: KeyboardEvent) {
    if (e.key === "Enter") (e.target as HTMLInputElement).blur();
  }
</script>

<div class="field" class:wide={f.kind === "longtext"} class:off class:bad={!!bad} use:context={items}>
  {#if f.kind === "bool"}
    <span class="label"></span>
    <label class="check">
      <input type="checkbox" checked={!!f.value} disabled={off} onchange={(e) => editField(f.key, (e.target as HTMLInputElement).checked)} />
      {f.label}
    </label>
  {:else}
    <div class="lw">
      <label class="label" for={"f-" + f.key} title={f.help ?? ""}>{f.label}{#if f.help}<span class="help">?</span>{/if}</label>
      {#if f.auto}
        <label class="auto" title="Size it from the neighbouring parts">
          <input type="checkbox" checked={f.isAuto} disabled={off} onchange={(e) => editField(f.auto!, (e.target as HTMLInputElement).checked)} />
          Auto
        </label>
      {/if}
    </div>
    <div class="control">
      {#if f.kind === "quantity"}
        <QuantityInput id={"f-" + f.key} value={f.value} dim={f.dim as Dim} disabled={f.isAuto || off} commit={(v) => editField(f.key, v)} />
      {:else if f.kind === "enum"}
        <select id={"f-" + f.key} class="select" value={f.value} disabled={off} onchange={(e) => editField(f.key, (e.target as HTMLSelectElement).value)}>
          {#each f.options ?? [] as o (o.value)}
            <option value={o.value}>{o.label}</option>
          {/each}
        </select>
      {:else if f.kind === "material"}
        <select id={"f-" + f.key} class="select" value={f.value} disabled={off} onchange={(e) => editField(f.key, (e.target as HTMLSelectElement).value)}>
          {#if !materials.some((m) => m.name === f.value)}<option value={f.value}>{f.value}</option>{/if}
          {#each materials as m (m.name)}
            <option value={m.name}>{m.name} ({f.materialType === "BULK" ? fmt(m.density, "DENSITY") : num(m.density, 3) + (f.materialType === "SURFACE" ? " kg/m²" : " kg/m")})</option>
          {/each}
        </select>
      {:else if f.kind === "count"}
        <div class="count">
          <button class="btn" disabled={off} onclick={() => editField(f.key, Math.max(0, (f.value ?? 0) - 1))} aria-label="Fewer">−</button>
          <input id={"f-" + f.key} class="input num" disabled={off} bind:value={text} onblur={(e) => commitNumber(text, e.target as HTMLInputElement)} onkeydown={enter} />
          <button class="btn" disabled={off} onclick={() => editField(f.key, (f.value ?? 0) + 1)} aria-label="More">+</button>
        </div>
      {:else if f.kind === "number"}
        <input id={"f-" + f.key} class="input num right" value={plain} disabled={off || f.isAuto}
          onblur={(e) => commitNumber((e.target as HTMLInputElement).value, e.target as HTMLInputElement)} onkeydown={enter} />
      {:else if f.kind === "longtext"}
        <textarea id={"f-" + f.key} rows="3" bind:value={text} onblur={commitText}></textarea>
      {:else}
        <input id={"f-" + f.key} class="input" bind:value={text} onblur={commitText} onkeydown={enter} />
      {/if}
    </div>
  {/if}
  {#if bad}
    <div class="err" role="alert">
      {bad.text}
      {#if bad.hint}<span class="hint">{bad.hint}</span>{/if}
    </div>
  {/if}
</div>

<style>
  .err {
    grid-column: 1 / -1;
    padding: 5px 8px;
    border-radius: var(--radius);
    background: var(--bad-soft);
    color: var(--ink);
    font-size: 12.5px;
    line-height: 1.35;
  }
  .err .hint {
    display: block;
    margin-top: 2px;
    color: var(--ink-2);
  }
  .field.bad :global(.input),
  .field.bad :global(.select) {
    border-color: var(--bad);
  }
  .field {
    display: grid;
    grid-template-columns: 44% 1fr;
    align-items: center;
    gap: 6px;
    min-height: var(--control);
  }
  .field.wide {
    grid-template-columns: 1fr;
  }
  .field.off .label,
  .field.off .check {
    color: var(--ink-3);
  }
  .label {
    color: var(--ink-2);
  }
  .lw {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }
  .help {
    display: inline-grid;
    place-items: center;
    width: 14px;
    height: 14px;
    margin-left: 4px;
    border-radius: 50%;
    background: var(--surface-3);
    color: var(--ink-3);
    font-size: 10px;
    cursor: help;
  }
  .control {
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
  }
  .control > :global(:first-child) {
    flex: 1;
  }
  .select,
  .input {
    width: 100%;
  }
  .right {
    text-align: right;
  }
  .auto {
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 11.5px;
    color: var(--ink-3);
  }
  .count {
    display: flex;
    gap: 3px;
  }
  .count .btn {
    width: 28px;
    justify-content: center;
    padding: 0;
  }
  .count .input {
    text-align: center;
  }
  textarea {
    width: 100%;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    padding: 4px 7px;
    background: var(--surface);
    resize: vertical;
  }
  .check {
    display: flex;
    align-items: center;
    gap: 6px;
    color: var(--ink-2);
  }
  :disabled {
    opacity: 0.6;
  }
</style>
