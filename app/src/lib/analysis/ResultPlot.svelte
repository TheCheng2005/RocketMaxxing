<script lang="ts">
  import Plot, { type Series } from "../Plot.svelte";
  import type { PlotSpec } from "./catalog";
  import { label } from "./text";

  // A plot of one list in a result. The engine writes values as text ("3205 m", "Fins.height = 150 mm"), so each is
  // read back as its number and unit.
  let { data, spec, name = "Analysis plot" }: { data: any; spec: PlotSpec; name?: string } = $props();

  const NUM = /[-+]?\d[\d,]*\.?\d*(?:[eE][-+]?\d+)?/;

  function read(v: unknown): { n: number | null; unit: string } {
    if (typeof v === "number") return { n: v, unit: "" };
    if (typeof v !== "string") return { n: null, unit: "" };
    const text = v.includes("=") ? v.slice(v.lastIndexOf("=") + 1) : v;
    const m = text.match(NUM);
    if (!m) return { n: null, unit: "" };
    const unit = text.slice((m.index ?? 0) + m[0].length).trim().split(/[\s(]/)[0] ?? "";
    return { n: Number(m[0].replace(/,/g, "")), unit };
  }

  const COLORS = ["#d9480f", "#2563c9", "#1f9d55", "#9b51e0", "#d63384"];

  let plot = $derived.by(() => {
    const rows: any[] = data?.[spec.list];
    if (!Array.isArray(rows) || rows.length < 2) return null;
    const xKey = spec.x ?? Object.keys(rows[0])[0];
    const xs = rows.map((r) => read(r[xKey]));
    if (xs.some((x) => x.n === null)) return null;
    const series: Series[] = [];
    const units: Record<string, string> = {};
    [...spec.left, ...(spec.right ?? [])].forEach((k, i) => {
      if (!(k in rows[0])) return;
      const vals = rows.map((r) => read(r[k]));
      units[k] = vals.find((v) => v.unit)?.unit ?? "";
      series.push({
        label: label(k) + (units[k] ? ` (${units[k]})` : ""),
        values: vals.map((v) => v.n),
        color: COLORS[i % COLORS.length],
        axis: spec.left.includes(k) ? "left" : "right",
      });
    });
    if (!series.length) return null;
    // Axis titles name the unit (the legend names the lines); a single line's axis also gets its name.
    const side = (keys: string[] | undefined) => {
      const ks = (keys ?? []).filter((k) => k in rows[0]);
      const us = [...new Set(ks.map((k) => units[k]).filter(Boolean))];
      return ks.length === 1 ? label(ks[0]) + (us[0] ? ` (${us[0]})` : "") : us.join(", ");
    };
    return {
      x: xs.map((x) => x.n as number),
      xLabel: label(xKey) + (xs[0].unit ? ` (${xs[0].unit})` : ""),
      series,
      left: side(spec.left),
      right: side(spec.right),
    };
  });
</script>

{#if plot}
  <div class="rp">
    <Plot x={plot?.x ?? []} series={plot?.series ?? []} xLabel={plot?.xLabel ?? ""} leftLabel={plot?.left ?? ""} rightLabel={plot?.right ?? ""} height={260} {name} title={name} />
  </div>
{/if}

<style>
  .rp {
    margin: 4px 0 10px;
  }
</style>
