<script lang="ts">
  import { untrack } from "svelte";
  import { Plus, Trash2 } from "@lucide/svelte";
  import { call } from "../api";
  import { app, act, select, quietly } from "../state.svelte";
  import { num, toShown, toSi, unitOf, pretty } from "../units.svelte";

  // A fin set's outline. Freeform fins are edited here: drag a point, double-click an edge to add one, select a point
  // and press Delete (or the bin) to remove it. Other fin sets show their outline and can be converted.
  let { component, type }: { component: string; type: string } = $props();

  interface Outline {
    points: [number, number][];
    editable: boolean;
    bodyRadius: number;
  }

  let outline = $state<Outline | null>(null);
  /** Why the outline is not shown, when it could not be read. */
  let failed = $state("");
  let pts = $state<[number, number][]>([]);
  let picked = $state(-1);
  let drag = $state<{ i: number; moved: boolean } | null>(null);
  let svg: SVGSVGElement | undefined = $state();

  $effect(() => {
    const id = component;
    app.revision; // reload after every edit, undo included
    untrack(() => load(id));
  });

  async function load(id: string) {
    try {
      const o = await call<Outline>("app_fin_points", { designId: app.design!.designId, component: id });
      if (id !== component) return;
      outline = o;
      pts = o.points.map((p) => [p[0], p[1]]);
      if (picked >= pts.length) picked = -1;
      failed = "";
    } catch (e) {
      outline = null;
      failed = quietly(e, "Fin shape");
    }
  }

  // The view keeps its scale while dragging, so points do not run away from the pointer.
  const W = 300;
  const H = 170;
  const PAD = 14;
  let frame = $state({ x0: 0, y0: 0, k: 1 });
  $effect(() => {
    if (drag || !outline) return;
    const xs = pts.map((p) => p[0]);
    const ys = pts.map((p) => p[1]);
    const x0 = Math.min(0, ...xs);
    const x1 = Math.max(...xs);
    const y1 = Math.max(...ys, 1e-3);
    const k = Math.min((W - 2 * PAD) / ((x1 - x0) * 1.15 || 1), (H - 2 * PAD - 16) / (y1 * 1.15));
    untrack(() => (frame = { x0: x0 - (x1 - x0) * 0.05, y0: 0, k }));
  });
  const sx = (x: number) => PAD + (x - frame.x0) * frame.k;
  const sy = (y: number) => H - PAD - 16 - y * frame.k;

  function toModel(e: PointerEvent | MouseEvent): [number, number] {
    const r = svg!.getBoundingClientRect();
    const px = ((e.clientX - r.left) / r.width) * W;
    const py = ((e.clientY - r.top) / r.height) * H;
    return [(px - PAD) / frame.k + frame.x0, (H - PAD - 16 - py) / frame.k];
  }

  function down(e: PointerEvent, i: number) {
    if (!outline?.editable || i === 0) return;
    picked = i;
    drag = { i, moved: false };
    (e.target as Element).setPointerCapture(e.pointerId);
  }

  function move(e: PointerEvent) {
    if (!drag) return;
    const [x, y] = toModel(e);
    const last = drag.i === pts.length - 1;
    pts[drag.i] = [x, last ? 0 : Math.max(0, y)];
    drag.moved = true;
  }

  function up() {
    if (drag?.moved) save();
    drag = null;
  }

  function save() {
    act("app_set_fin_points", { component, points: pts.map((p) => [p[0], p[1]]) });
  }

  /** Double-click near an edge (not the root) to add a point there. */
  function insert(e: MouseEvent) {
    if (!outline?.editable) return;
    const [x, y] = toModel(e);
    let best = -1;
    let dist = Infinity;
    for (let i = 0; i < pts.length - 1; i++) {
      const d = segDist([x, y], pts[i], pts[i + 1]);
      if (d < dist) [dist, best] = [d, i];
    }
    if (best < 0 || dist * frame.k > 14) return;
    pts.splice(best + 1, 0, [x, Math.max(0, y)]);
    picked = best + 1;
    save();
  }

  function segDist(p: [number, number], a: [number, number], b: [number, number]) {
    const [dx, dy] = [b[0] - a[0], b[1] - a[1]];
    const t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy || 1)));
    return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy);
  }

  function removePicked() {
    if (picked <= 0 || picked >= pts.length - 1 || pts.length <= 3) return;
    pts.splice(picked, 1);
    picked = -1;
    save();
  }

  function addAfter(i: number) {
    const a = pts[i];
    const b = pts[i + 1] ?? [a[0] + 0.01, 0];
    pts.splice(i + 1, 0, [(a[0] + b[0]) / 2, Math.max(0, (a[1] + b[1]) / 2)]);
    save();
  }

  function typed(i: number, axis: 0 | 1, text: string) {
    const v = Number(text.replace(/,/g, ""));
    if (!Number.isFinite(v)) return;
    pts[i][axis] = toSi(v, "LENGTH");
    save();
  }

  async function convert() {
    const r = await act("app_convert_fins", { component });
    if (r?.component) await select(r.component);
  }

  function key(e: KeyboardEvent) {
    if ((e.key === "Delete" || e.key === "Backspace") && picked > 0) {
      e.preventDefault();
      e.stopPropagation();
      removePicked();
    }
  }

  let path = $derived(pts.map((p, i) => `${i ? "L" : "M"}${sx(p[0])},${sy(p[1])}`).join(" ") + " Z");
  const u = $derived(pretty(unitOf("LENGTH").unit));
</script>

{#if outline}
  <div class="fin">
    <!-- The table below edits the same points from the keyboard. -->
    <!-- svelte-ignore a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions -->
    <svg
      bind:this={svg}
      viewBox="0 0 {W} {H}"
      class:edit={outline.editable}
      tabindex={outline.editable ? 0 : -1}
      role="application"
      aria-label="Fin outline"
      onpointermove={move}
      onpointerup={up}
      ondblclick={insert}
      onkeydown={key}
    >
      <rect x="0" y={sy(0)} width={W} height="16" class="body" />
      <path d={path} class="outline" />
      {#if outline.editable}
        {#each pts as p, i (i)}
          <!-- svelte-ignore a11y_no_static_element_interactions -->
          <circle
            cx={sx(p[0])}
            cy={sy(p[1])}
            r={i === picked ? 6 : 4.5}
            class="pt"
            class:fixed={i === 0}
            class:on={i === picked}
            onpointerdown={(e) => down(e, i)}
          />
        {/each}
      {/if}
    </svg>
    {#if outline.editable}
      <p class="hint">Drag points; double-click an edge to add one; select a point and press Delete to remove it.</p>
      <table>
        <thead><tr><th></th><th>x ({u})</th><th>y ({u})</th><th></th></tr></thead>
        <tbody>
          {#each pts as p, i (i)}
            <tr class:on={i === picked}>
              <td class="n">{i + 1}</td>
              <td><input class="input" value={num(toShown(p[0], "LENGTH"))} disabled={i === 0} onchange={(e) => typed(i, 0, e.currentTarget.value)} /></td>
              <td><input class="input" value={num(toShown(p[1], "LENGTH"))} disabled={i === 0 || i === pts.length - 1} onchange={(e) => typed(i, 1, e.currentTarget.value)} /></td>
              <td class="acts">
                {#if i < pts.length - 1}<button class="tool" title="Add a point after this one" onclick={() => addAfter(i)}><Plus size={13} /></button>{/if}
                {#if i > 0 && i < pts.length - 1 && pts.length > 3}<button class="tool" title="Remove this point" onclick={() => { picked = i; removePicked(); }}><Trash2 size={13} /></button>{/if}
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    {:else if type !== "TubeFinSet"}
      <div class="convert">
        <span class="hint">To draw your own outline, convert these fins to freeform (undo brings them back).</span>
        <button class="btn" onclick={convert}>Convert to freeform</button>
      </div>
    {/if}
  </div>
{:else if failed}
  <p class="muted">The fin outline could not be read: {failed}</p>
{/if}

<style>
  .fin {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  svg {
    width: 100%;
    background: var(--surface-2);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    touch-action: none;
  }
  svg.edit:focus {
    outline: 2px solid var(--select);
    outline-offset: -1px;
  }
  .body {
    fill: var(--surface-3);
  }
  .outline {
    fill: color-mix(in srgb, var(--select) 18%, transparent);
    stroke: var(--select);
    stroke-width: 1.5;
    stroke-linejoin: round;
  }
  .pt {
    fill: var(--surface);
    stroke: var(--select);
    stroke-width: 2;
    cursor: grab;
  }
  .pt.on {
    fill: var(--select);
  }
  .pt.fixed {
    stroke: var(--ink-3);
    cursor: default;
  }
  .hint {
    margin: 0;
    font-size: 11.5px;
    color: var(--ink-3);
  }
  table {
    border-collapse: collapse;
  }
  th {
    font-size: 11.5px;
    font-weight: 600;
    color: var(--ink-3);
    text-align: left;
  }
  td {
    padding: 1px 2px;
  }
  tr.on td {
    background: var(--surface-2);
  }
  .n {
    color: var(--ink-3);
    font-size: 11.5px;
    width: 1%;
  }
  .input {
    width: 84px;
    text-align: right;
  }
  .acts {
    white-space: nowrap;
  }
  .tool {
    display: inline-grid;
    place-items: center;
    width: 22px;
    height: 22px;
    border: 0;
    background: transparent;
    color: var(--ink-3);
    border-radius: 3px;
  }
  .tool:hover {
    background: var(--surface-3);
    color: var(--ink);
  }
  .convert {
    display: flex;
    flex-direction: column;
    gap: 6px;
    align-items: flex-start;
  }
</style>
