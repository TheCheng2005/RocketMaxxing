<script lang="ts">
  import { fmt, toShown, unitOf, pretty, num } from "../units.svelte";

  // Predicted against measured apogee, one dot per flight; dots on the diagonal were predicted exactly.
  let { points }: { points: { measured: number; predicted: number; label: string }[] } = $props();

  const W = 420;
  const H = 260;
  const M = { l: 58, r: 12, t: 10, b: 38 };

  // Both axes in the chosen distance unit, over the same range.
  let c = $derived.by(() => {
    if (!points.length) return null;
    const vals = points.flatMap((p) => [p.measured, p.predicted]).map((v) => toShown(v, "DISTANCE"));
    const pad = (Math.max(...vals) - Math.min(...vals) || Math.max(...vals) || 1) * 0.12;
    const lo = Math.max(0, Math.min(...vals) - pad);
    const hi = Math.max(...vals) + pad;
    const step = nice((hi - lo) / 5);
    const ticks: number[] = [];
    for (let v = Math.ceil(lo / step) * step; v <= hi; v += step) ticks.push(v);
    const x = (v: number) => M.l + ((v - lo) / (hi - lo)) * (W - M.l - M.r);
    const y = (v: number) => H - M.b - ((v - lo) / (hi - lo)) * (H - M.t - M.b);
    return { lo, hi, ticks, x, y };
  });

  function nice(raw: number): number {
    const p = 10 ** Math.floor(Math.log10(raw || 1));
    const f = raw / p;
    return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * p;
  }
</script>

{#if c}
  <svg viewBox="0 0 {W} {H}" class="chart" role="img" aria-label="Predicted against measured apogee">
    {#each c.ticks as v (v)}
      <line x1={c.x(v)} x2={c.x(v)} y1={M.t} y2={H - M.b} class="grid" />
      <line x1={M.l} x2={W - M.r} y1={c.y(v)} y2={c.y(v)} class="grid" />
      <text x={c.x(v)} y={H - M.b + 14} text-anchor="middle">{num(v)}</text>
      <text x={M.l - 6} y={c.y(v) + 4} text-anchor="end">{num(v)}</text>
    {/each}
    <line x1={c.x(c.lo)} y1={c.y(c.lo)} x2={c.x(c.hi)} y2={c.y(c.hi)} class="diag" />
    <text x={(M.l + W - M.r) / 2} y={H - 4} text-anchor="middle" class="axis">Measured apogee ({pretty(unitOf("DISTANCE").unit)})</text>
    <text transform="translate(12 {(M.t + H - M.b) / 2}) rotate(-90)" text-anchor="middle" class="axis">Predicted ({pretty(unitOf("DISTANCE").unit)})</text>
    {#each points as p, i (i)}
      <circle cx={c.x(toShown(p.measured, "DISTANCE"))} cy={c.y(toShown(p.predicted, "DISTANCE"))} r="5" class="dot">
        <title>{p.label}: predicted {fmt(p.predicted, "DISTANCE")}, measured {fmt(p.measured, "DISTANCE")}</title>
      </circle>
    {/each}
  </svg>
{/if}

<style>
  .chart {
    width: 100%;
    max-width: 520px;
    display: block;
  }
  text {
    font-size: calc(11px * var(--text));
    fill: var(--ink-3);
  }
  .axis {
    font-size: calc(12px * var(--text));
    fill: var(--ink-2);
  }
  .grid {
    stroke: var(--line);
    stroke-width: 1;
  }
  .diag {
    stroke: var(--ink-3);
    stroke-dasharray: 4 4;
  }
  .dot {
    fill: var(--select);
    fill-opacity: 0.8;
    stroke: var(--surface);
    stroke-width: 1.5;
  }
</style>
