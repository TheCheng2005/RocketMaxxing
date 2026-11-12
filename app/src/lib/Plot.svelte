<script module lang="ts">
  export interface Series {
    label: string;
    values: (number | null)[];
    color: string;
    axis?: "left" | "right";
  }

  /** True when the x column never goes backwards: then it is an ordinary plot against x, with readouts under the cursor. */
  export function ascending(x: (number | null)[]): boolean {
    for (let i = 1; i < x.length; i++) {
      const a = x[i - 1], b = x[i];
      if (a === null || b === null || !(b >= a)) return false;
    }
    return x.length === 0 || x[0] !== null;
  }

  /** The plotted numbers as CSV: the x column, then each series. */
  export function plotCsv(xLabel: string, x: (number | null)[], series: Series[]): string {
    const q = (s: string) => `"${s.replace(/"/g, '""')}"`;
    const head = [xLabel || "x", ...series.map((s) => s.label)].map(q).join(",");
    const rows = x.map((v, k) => [v ?? "", ...series.map((s) => s.values[k] ?? "")].join(","));
    return [head, ...rows].join("\n");
  }
</script>

<script lang="ts">
  import { onMount } from "svelte";
  import uPlot from "uplot";
  import "uplot/dist/uPlot.min.css";
  import { Download, ZoomOut } from "@lucide/svelte";
  import { call, connected } from "./api";
  import { toast } from "./state.svelte";

  // A line plot: one x column, any number of y series on up to two axes (left and right), event lines.
  // Drag a box to zoom in (a mostly sideways drag zooms along x only); double-click or "Reset zoom" to see it all again.
  // When x goes backwards (altitude against velocity, a ground track) the curve is drawn in the order of the samples.

  let {
    x,
    series,
    xLabel = "",
    leftLabel = "",
    rightLabel = "",
    events = [],
    height = 280,
    name = "plot",
    title = "",
    tools = true,
  }: {
    x: (number | null)[];
    series: Series[];
    xLabel?: string;
    leftLabel?: string;
    rightLabel?: string;
    /** Marked moments: a line at x, or a point at (x, y) on the left axis when x is not ascending. */
    events?: { x: number; label: string; y?: number | null }[];
    height?: number;
    /** File name for exports, without the extension. */
    name?: string;
    /** Heading written on the exported picture. */
    title?: string;
    /** The zoom and export buttons above the plot. */
    tools?: boolean;
  } = $props();

  let host: HTMLDivElement;
  let plot: uPlot | null = null;
  let zoomed = $state(false);
  let busy = $state(false);

  const css = (n: string) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();

  function make() {
    plot?.destroy();
    plot = null;
    zoomed = false;
    if (!host || x.length === 0) return;
    const ink = css("--ink-2");
    const grid = css("--grid");
    const hasRight = series.some((s) => s.axis === "right");
    const xy = !ascending(x);
    const axisStyle = { stroke: ink, grid: { stroke: grid, width: 1 }, ticks: { stroke: grid, width: 1 } };
    const scaleOf = (s: Series) => (s.axis === "right" ? "right" : "left");
    const opts: uPlot.Options = {
      width: host.clientWidth || 600,
      height,
      ...(xy ? { mode: 2 as const } : {}),
      scales: { x: { time: false } },
      legend: { live: !xy },
      // A box zooms both ways; a drag that stays within 30 px of level (or of upright) zooms along that direction only.
      cursor: { drag: { x: true, y: true, uni: 30 } },
      series: [
        xy ? {} : { label: xLabel },
        ...series.map((s) =>
          xy
            ? { label: s.label, stroke: s.color, width: 2, paths: uPlot.paths.linear!(), points: { show: false },
                facets: [{ scale: "x", auto: true }, { scale: scaleOf(s), auto: true }] }
            : { label: s.label, stroke: s.color, width: 2, scale: scaleOf(s), spanGaps: false },
        ),
      ] as uPlot.Series[],
      axes: [
        { ...axisStyle, label: xLabel },
        { ...axisStyle, scale: "left", label: leftLabel },
        ...(hasRight ? [{ ...axisStyle, scale: "right", side: 1 as const, label: rightLabel, grid: { show: false } }] : []),
      ],
      hooks: {
        // A finished drag leaves a selection behind for a moment: that is the zoom.
        setSelect: [(u: uPlot) => { if (u.select.width > 0 || u.select.height > 0) zoomed = true; }],
      },
      plugins: [
        {
          hooks: {
            draw: [
              (u: uPlot) => {
                const ctx = u.ctx;
                const r = devicePixelRatio;
                const inside = (px: number, py: number) =>
                  px >= u.bbox.left && px <= u.bbox.left + u.bbox.width && py >= u.bbox.top && py <= u.bbox.top + u.bbox.height;
                ctx.save();
                ctx.font = `${11 * r}px sans-serif`;
                ctx.fillStyle = css("--ink-3");
                ctx.strokeStyle = css("--ink-3");
                const placed: [number, number][] = [];
                for (const ev of events) {
                  const px = u.valToPos(ev.x, "x", true);
                  if (xy) {
                    // A point on the curve, named beside it.
                    if (ev.y === undefined || ev.y === null) continue;
                    const py = u.valToPos(ev.y, "left", true);
                    if (!inside(px, py)) continue;
                    ctx.beginPath();
                    ctx.arc(px, py, 3 * r, 0, 2 * Math.PI);
                    ctx.fill();
                    // Events that fall on the same spot (apogee, ejection) are listed under one another.
                    let ty = py - 5 * r;
                    while (placed.some(([qx, qy]) => Math.abs(qx - px) < 60 * r && Math.abs(qy - ty) < 11 * r)) ty += 12 * r;
                    placed.push([px, ty]);
                    ctx.textAlign = px > u.bbox.left + u.bbox.width - 70 * r ? "right" : "left";
                    ctx.fillText(ev.label, px + (ctx.textAlign === "right" ? -6 : 6) * r, ty);
                    continue;
                  }
                  if (px < u.bbox.left || px > u.bbox.left + u.bbox.width) continue;
                  ctx.setLineDash([4 * r, 4 * r]);
                  ctx.beginPath();
                  ctx.moveTo(px, u.bbox.top);
                  ctx.lineTo(px, u.bbox.top + u.bbox.height);
                  ctx.stroke();
                  // Labels run up the line, so events close together stay readable.
                  ctx.save();
                  ctx.translate(px - 3 * r, u.bbox.top + 4 * r);
                  ctx.rotate(-Math.PI / 2);
                  ctx.textAlign = "right";
                  ctx.fillText(ev.label, 0, 0);
                  ctx.restore();
                }
                ctx.restore();
              },
            ],
          },
        },
      ],
    };
    const data = xy ? [null, ...series.map((s) => [x, s.values])] : [x, ...series.map((s) => s.values)];
    plot = new uPlot(opts, data as unknown as uPlot.AlignedData, host);
    plot.over.addEventListener("dblclick", () => (zoomed = false));
  }

  /** Shows the whole curve again. */
  function reset() {
    if (!plot) return;
    plot.setData(plot.data);
    zoomed = false;
  }

  /** The plot as a picture: its heading, the curves as they are on screen (zoom included) and the legend, on the page's background. */
  function picture(): HTMLCanvasElement | null {
    if (!plot) return null;
    const src = plot.ctx.canvas;
    const r = src.width / (plot.width || 1);
    const pad = 12 * r, line = 18 * r;
    const out = document.createElement("canvas");
    const c = out.getContext("2d")!;
    // The legend wraps to the picture's width.
    c.font = `${12 * r}px sans-serif`;
    const items = series.map((s) => ({ s, w: c.measureText(s.label).width + 30 * r }));
    const rows: (typeof items)[] = [[]];
    let used = 0;
    for (const it of items) {
      if (used + it.w > src.width - 2 * pad && rows[rows.length - 1].length) {
        rows.push([]);
        used = 0;
      }
      rows[rows.length - 1].push(it);
      used += it.w;
    }
    const top = title ? line + pad : pad / 2;
    out.width = src.width;
    out.height = top + src.height + rows.length * line + pad;
    let bg = "#ffffff";
    for (let el: HTMLElement | null = host; el; el = el.parentElement) {
      const b = getComputedStyle(el).backgroundColor;
      if (b && b !== "transparent" && !/rgba\(.*,\s*0\)$/.test(b)) {
        bg = b;
        break;
      }
    }
    c.fillStyle = bg;
    c.fillRect(0, 0, out.width, out.height);
    c.textBaseline = "middle";
    if (title) {
      c.font = `600 ${13 * r}px sans-serif`;
      c.fillStyle = css("--ink") || "#111";
      c.fillText(title, pad, pad / 2 + line / 2);
    }
    c.drawImage(src, 0, top);
    c.font = `${12 * r}px sans-serif`;
    rows.forEach((row, k) => {
      let px = pad;
      const y = top + src.height + k * line + line / 2;
      for (const { s, w } of row) {
        c.fillStyle = s.color;
        c.fillRect(px, y - 1.5 * r, 18 * r, 3 * r);
        c.fillStyle = css("--ink-2") || "#444";
        c.fillText(s.label, px + 24 * r, y);
        px += w;
      }
    });
    return out;
  }

  /** Saves the picture or the numbers in the Downloads folder (through the browser when there is no engine). */
  async function save(kind: "png" | "csv") {
    if (busy) return;
    busy = true;
    const file = `${name || "plot"}.${kind}`;
    try {
      const canvas = kind === "png" ? picture() : null;
      const text = kind === "csv" ? plotCsv(xLabel, x, series) : "";
      if (kind === "png" && !canvas) return;
      if (connected) {
        const body = canvas ? { base64: canvas.toDataURL("image/png").split(",")[1] } : { text };
        const r = await call<{ path: string; folderName: string }>("app_export", { name: file, ...body });
        toast(`Saved ${r.path.split(/[\\/]/).pop()} in ${r.folderName}`);
      } else {
        const a = document.createElement("a");
        a.href = canvas ? canvas.toDataURL("image/png") : URL.createObjectURL(new Blob([text], { type: "text/csv" }));
        a.download = file;
        a.click();
      }
    } catch (e: any) {
      toast(`Could not save the plot: ${e?.message ?? e}`, "error");
    } finally {
      busy = false;
    }
  }

  onMount(() => {
    const ro = new ResizeObserver(() => plot?.setSize({ width: host.clientWidth, height }));
    ro.observe(host);
    return () => {
      ro.disconnect();
      plot?.destroy();
    };
  });

  $effect(() => {
    void x;
    void series;
    void events;
    void xLabel;
    void leftLabel;
    void rightLabel;
    make();
  });
</script>

{#if tools && x.length > 0}
  <div class="bar">
    <span class="hint">{zoomed ? "Zoomed in" : "Drag on the plot to zoom"}</span>
    {#if zoomed}<button class="pbtn" onclick={reset} title="Show the whole curve again (or double-click the plot)"><ZoomOut size={13} /> Reset zoom</button>{/if}
    <button class="pbtn" onclick={() => save("png")} disabled={busy} title="Save this plot as a picture, as it is shown now"><Download size={13} /> PNG</button>
    <button class="pbtn" onclick={() => save("csv")} disabled={busy} title="Save the plotted numbers as a spreadsheet file"><Download size={13} /> CSV</button>
  </div>
{/if}
<div class="plot" bind:this={host}></div>

<style>
  .plot {
    width: 100%;
    min-width: 0;
  }
  .plot :global(.u-legend) {
    font-size: 12px;
    color: var(--ink-2);
  }
  .plot :global(.u-select) {
    background: color-mix(in srgb, var(--select) 15%, transparent);
    border: 1px solid color-mix(in srgb, var(--select) 60%, transparent);
  }
  .bar {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 4px;
    min-height: 22px;
  }
  .hint {
    margin-right: auto;
    font-size: 11.5px;
    color: var(--ink-3);
  }
  .pbtn {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    height: 22px;
    padding: 0 7px;
    font: inherit;
    font-size: 11.5px;
    color: var(--ink-2);
    background: transparent;
    border: 1px solid var(--line, color-mix(in srgb, var(--ink-3) 35%, transparent));
    border-radius: 5px;
    cursor: pointer;
  }
  .pbtn:hover:not(:disabled) {
    color: var(--ink);
    background: color-mix(in srgb, var(--ink-3) 12%, transparent);
  }
  .pbtn:disabled {
    opacity: 0.5;
    cursor: default;
  }
</style>
