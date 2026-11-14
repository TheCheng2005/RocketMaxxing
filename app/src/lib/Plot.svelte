<script module lang="ts">
  export interface Series {
    label: string;
    values: (number | null)[];
    color: string;
    axis?: "left" | "right";
    /** This curve's own x column, when the curves do not share one (two simulations, a flight log). */
    x?: (number | null)[];
    /** Line pattern, to tell apart the same quantity from two flights. */
    dash?: number[];
    /** The time of each sample, shown under the cursor when x is not time. */
    t?: (number | null)[];
  }

  /** True when the x column never goes backwards: then it is an ordinary plot against x, with readouts under the cursor. */
  export function ascending(x: (number | null)[]): boolean {
    for (let i = 1; i < x.length; i++) {
      const a = x[i - 1], b = x[i];
      if (a === null || b === null || !(b >= a)) return false;
    }
    return x.length === 0 || x[0] !== null;
  }

  /** The plotted numbers as CSV: the x column, then each series; curves with their own x column get one each. */
  export function plotCsv(xLabel: string, x: (number | null)[], series: Series[]): string {
    const q = (s: string) => `"${s.replace(/"/g, '""')}"`;
    if (!series.some((s) => s.x)) {
      const head = [xLabel || "x", ...series.map((s) => s.label)].map(q).join(",");
      const rows = x.map((v, k) => [v ?? "", ...series.map((s) => s.values[k] ?? "")].join(","));
      return [head, ...rows].join("\n");
    }
    const head = series.flatMap((s) => [`${xLabel || "x"}: ${s.label}`, s.label]).map(q).join(",");
    const n = Math.max(0, ...series.map((s) => s.values.length));
    const rows = Array.from({ length: n }, (_, k) => series.flatMap((s) => [(s.x ?? x)[k] ?? "", s.values[k] ?? ""]).join(","));
    return [head, ...rows].join("\n");
  }

  /** The sample of an ascending column nearest to v. */
  export function nearest(col: (number | null)[], v: number): number {
    let lo = 0, hi = col.length - 1;
    while (hi - lo > 1) {
      const mid = (lo + hi) >> 1;
      if ((col[mid] ?? -Infinity) <= v) lo = mid;
      else hi = mid;
    }
    return hi >= 0 && Math.abs((col[hi] ?? Infinity) - v) < Math.abs((col[lo] ?? Infinity) - v) ? hi : lo;
  }
</script>

<script lang="ts">
  import { onMount, untrack } from "svelte";
  import uPlot from "uplot";
  import "uplot/dist/uPlot.min.css";
  import { Download, ZoomIn, ZoomOut, Maximize2, SlidersHorizontal } from "@lucide/svelte";
  import { context, type MenuItem } from "./menu/menu.svelte";
  import { call, connected } from "./api";
  import { toast } from "./state.svelte";
  import { num } from "./units.svelte";

  // A line plot: one x column, any number of y series on up to two axes (left and right), event lines.
  // Zooming: drag a box, turn the wheel (about the pointer), or use the + and - buttons; "Zoom" picks whether that
  // changes both directions, x only or y only. The wheel over one axis zooms that axis alone and a double-click on it
  // resets it; Shift-drag or the middle button moves the view; "Fit" or a double-click on the plot shows it all again.
  // "Ranges" takes typed limits for each axis.
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
    pickAxis,
  }: {
    x: (number | null)[];
    series: Series[];
    xLabel?: string;
    leftLabel?: string;
    rightLabel?: string;
    /** Marked moments: a line at x, or with y a point at (x, y) on that axis (left unless said). */
    events?: { x: number; label: string; y?: number | null; axis?: "left" | "right" }[];
    height?: number;
    /** File name for exports, without the extension. */
    name?: string;
    /** Heading written on the exported picture. */
    title?: string;
    /** The zoom and export buttons above the plot. */
    tools?: boolean;
    /** When given, a right-click on a curve's name offers to move it to the left or right axis. */
    pickAxis?: (index: number, side: "left" | "right") => void;
  } = $props();

  let host: HTMLDivElement;
  let plot: uPlot | null = null;
  let zoomed = $state(false);
  let busy = $state(false);
  /** Curves with their own x columns, or an x that goes backwards: drawn point by point, with our own readout. */
  let xy = $derived(series.some((s) => s.x) || !ascending(x));
  /** Every curve's x column goes forwards: the readout is each curve's value at the pointer's x. */
  let forwards = $derived(series.every((s) => ascending(s.x ?? x)));
  /** What is under the pointer: a heading, each curve's value, and (for the nearest point of a path) its mark. */
  let readout = $state<{ head: string; values: string[]; dot?: { left: number; top: number; color: string } } | null>(null);
  /** Curves switched off in the legend, by name, so they stay off when the data is refreshed. */
  let off = $state<string[]>([]);
  /** The scales showing everything, to stop the wheel zooming out past the data. */
  let full: [string, number, number][] = [];
  /** Set while the plot is showing all of its data for the first time since it was made or reset. */
  let fresh = true;
  /** Which directions a box, the wheel and the zoom buttons change. */
  type Mode = "both" | "x" | "y";
  let mode = $state<Mode>("both");
  /** The limits on screen, for the Ranges editor. */
  let limits = $state<Record<string, [number, number]>>({});
  let ranging = $state(false);
  /** Y ranges set by hand (a typed limit, a zoom along y): kept when x is zoomed or moved, until that axis is fitted again. */
  let held: Record<string, [number, number]> = {};
  const isZoomed = (u: uPlot) => ranges(u).some(([k, a, b]) => { const w = full.find(([q]) => q === k); return !!w && (a !== w[1] || b !== w[2]); });
  /** Sets scales together: x first, then y, so a y range set by hand is not replaced by the fit that follows a change of x. */
  function setScales(u: uPlot, want: Record<string, [number, number]>) {
    for (const [k, r] of Object.entries(want)) if (k !== "x") held[k] = r;
    u.batch(() => {
      if (want.x) u.setScale("x", { min: want.x[0], max: want.x[1] });
      for (const [k, r] of Object.entries({ ...held, ...want })) if (k !== "x") u.setScale(k, { min: r[0], max: r[1] });
    });
  }
  const AXIS_NAME: Record<string, string> = { x: "X axis", left: "Left axis", right: "Right axis" };

  const css = (n: string) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();

  function make() {
    plot?.destroy();
    plot = null;
    zoomed = false;
    held = {};
    if (!host || x.length === 0) return;
    const ink = css("--ink-2");
    const grid = css("--grid");
    const hasRight = series.some((s) => s.axis === "right");
    readout = null;
    const axisStyle = { stroke: ink, grid: { stroke: grid, width: 1 }, ticks: { stroke: grid, width: 1 } };
    const scaleOf = (s: Series) => (s.axis === "right" ? "right" : "left");
    const hiddenNow = untrack(() => off);
    const show = (s: Series) => !hiddenNow.includes(s.label);
    const opts: uPlot.Options = {
      width: host.clientWidth || 600,
      height,
      ...(xy ? { mode: 2 as const } : {}),
      scales: { x: { time: false } },
      legend: { show: false },
      // A box zooms both ways; a drag that stays within 30 px of level (or of upright) zooms along that direction only.
      cursor: { drag: untrack(() => mode) === "both" ? { x: true, y: true, uni: 30 } : { x: untrack(() => mode) === "x", y: untrack(() => mode) === "y" } },
      series: [
        xy ? {} : { label: xLabel },
        ...series.map((s) =>
          xy
            ? { label: s.label, stroke: s.color, width: 2, dash: s.dash, show: show(s), paths: uPlot.paths.linear!(), points: { show: false },
                facets: [{ scale: "x", auto: true }, { scale: scaleOf(s), auto: true }] }
            : { label: s.label, stroke: s.color, width: 2, dash: s.dash, show: show(s), scale: scaleOf(s), spanGaps: false },
        ),
      ] as uPlot.Series[],
      axes: [
        { ...axisStyle, label: xLabel },
        { ...axisStyle, scale: "left", label: leftLabel },
        ...(hasRight ? [{ ...axisStyle, scale: "right", side: 1 as const, label: rightLabel, grid: { show: false } }] : []),
      ],
      hooks: {
        // A finished drag leaves a selection behind for a moment: that is the zoom.
        // A finished box: its y ranges are now set by hand, and the ones set before stay.
        setSelect: [(u: uPlot) => {
          if (!(u.select.width > 0 || u.select.height > 0)) return;
          const m = untrack(() => mode);
          zoomed = true;
          // After uPlot has applied the box (it does that a moment later).
          setTimeout(() => {
            if (plot !== u) return;
            const now = Object.fromEntries(ranges(u).filter(([k]) => k !== "x").map(([k, a, b]) => [k, [a, b] as [number, number]]));
            setScales(u, m === "x" ? {} : now);
          }, 0);
        }],
        setCursor: [(u: uPlot) => point(u)],
        // uPlot applies scale changes a moment after they are asked for: what is on screen is read here.
        setScale: [(u: uPlot) => {
          limits = Object.fromEntries(ranges(u).map(([k, a, b]) => [k, [a, b]]));
          if (fresh) {
            // The first ranges after the data is set are the whole curve.
            full = ranges(u);
            queueMicrotask(() => (fresh = false));
          } else zoomed = isZoomed(u);
        }],
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
                  if (ev.y !== undefined) {
                    // A point on the curve, named beside it.
                    if (ev.y === null) continue;
                    const py = u.valToPos(ev.y, ev.axis === "right" ? "right" : "left", true);
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
    // A sample without an x has nowhere to be drawn: it becomes a gap.
    const pair = (s: Series) => {
      const col = s.x ?? x;
      return col.some((v) => v === null) ? [col.map((v) => v ?? 0), s.values.map((v, k) => (col[k] === null ? null : v))] : [col, s.values];
    };
    const data = xy ? [null, ...series.map(pair)] : [x, ...series.map((s) => s.values)];
    fresh = true;
    plot = new uPlot(opts, data as unknown as uPlot.AlignedData, host);
    if (ranges(plot).length) full = ranges(plot);
    const over = plot.over;
    over.addEventListener("dblclick", reset);
    over.addEventListener("wheel", wheel, { passive: false });
    over.addEventListener("mousedown", grab, true);
    over.addEventListener("mouseleave", () => (readout = null));
    // Each axis by itself: the wheel over it zooms it, a double-click fits it again.
    const keys = ["x", "left", ...(hasRight ? ["right"] : [])];
    plot.root.querySelectorAll<HTMLElement>(".u-axis").forEach((el, i) => {
      const key = keys[i];
      if (!key) return;
      el.style.pointerEvents = "auto";
      el.title = `${AXIS_NAME[key]}: scroll here to zoom it alone, double-click to fit it again`;
      el.addEventListener("wheel", (e) => {
        if (!e.deltaY) return;
        e.preventDefault();
        const box = el.getBoundingClientRect();
        const f = key === "x" ? (e.clientX - box.left) / box.width : 1 - (e.clientY - box.top) / box.height;
        zoomBy(Math.exp(Math.sign(e.deltaY) * Math.min(Math.abs(e.deltaY), 60) * 0.004), [key], f, f);
      }, { passive: false });
      el.addEventListener("dblclick", () => fit(key));
    });
    limits = Object.fromEntries(full.map(([k, a, b]) => [k, [a, b]]));
  }

  /** The scales a zoom changes in this mode. */
  function scalesFor(m: Mode): string[] {
    return ranges(plot!).map(([k]) => k).filter((k) => m === "both" || (m === "x") === (k === "x"));
  }

  /** Zooms the named scales by k (under 1 is closer) about the point at fractions fx, fy of the plot; never out past the data. */
  function zoomBy(k: number, keys: string[], fx = 0.5, fy = 0.5) {
    const u = plot;
    if (!u) return;
    const want: Record<string, [number, number]> = {};
    for (const [key, min, max] of ranges(u)) {
      if (!keys.includes(key)) continue;
      const whole = full.find(([q]) => q === key);
      const span = (max - min) * k;
      if (whole && k < 1 && span < (whole[2] - whole[1]) * 1e-6) continue; // the numbers run out of digits
      if (whole && k > 1 && span >= whole[2] - whole[1]) {
        if (key === "x") want.x = [whole[1], whole[2]];
        else fitY(key);
        continue;
      }
      const f = key === "x" ? fx : fy, at = min + f * (max - min);
      want[key] = [at - (at - min) * k, at + (max - at) * k];
    }
    setScales(u, want);
  }

  /** A y axis goes back to fitting what is on screen. */
  function fitY(key: string) {
    delete held[key];
  }

  /** One axis back to showing all of its data. */
  function fit(key: string) {
    const u = plot, w = full.find(([q]) => q === key);
    if (!u || !w) return;
    if (key === "x") return setScales(u, { x: [w[1], w[2]] });
    fitY(key);
    // With all of x on screen that is the whole curve; zoomed in along x, it is what is in view.
    const x = ranges(u).find(([q]) => q === "x"), wx = full.find(([q]) => q === "x");
    if (x && wx && x[1] === wx[1] && x[2] === wx[2]) u.setScale(key, { min: w[1], max: w[2] });
    else if (x) u.setScale("x", { min: x[1], max: x[2] }); // uPlot fits the y axes that are not held
    setScales(u, {});
  }

  /** A typed limit for one end of an axis. A refused one is put back as it was. */
  function setLimit(key: string, end: 0 | 1, el: HTMLInputElement) {
    const u = plot, now = limits[key];
    const text = el.value.trim();
    const v = Number(text.replace(",", "."));
    if (!u || !now) return;
    const back = (why: string) => {
      el.value = num(now[end], 5).replace(/,/g, "");
      toast(why, "error");
    };
    if (!Number.isFinite(v) || text === "") return back("An axis limit is a number.");
    const next: [number, number] = end === 0 ? [v, now[1]] : [now[0], v];
    if (!(next[0] < next[1])) return back(`${AXIS_NAME[key] ?? key}: the lower limit has to be below the upper one.`);
    setScales(u, { [key]: next });
  }

  /** Changing what zooming does keeps the view: the plot is rebuilt for the new kind of box, then put back where it was. */
  function setMode(m: Mode) {
    if (m === mode) return;
    const was = plot ? ranges(plot) : [], z = zoomed;
    mode = m;
    make();
    if (plot && z) setScales(plot, Object.fromEntries(was.map(([k, a, b]) => [k, [a, b] as [number, number]])));
  }

  /** The value range of every scale, to zoom or move them together. */
  function ranges(u: uPlot): [string, number, number][] {
    return Object.entries(u.scales).flatMap(([k, sc]) => (sc.min == null || sc.max == null ? [] : [[k, sc.min, sc.max] as [string, number, number]]));
  }

  /** The wheel zooms about the pointer in the chosen directions; Ctrl, Cmd or a pinch always zooms both. */
  function wheel(e: WheelEvent) {
    const u = plot;
    if (!u || !e.deltaY) return;
    e.preventDefault();
    const box = u.over.getBoundingClientRect();
    const fx = (e.clientX - box.left) / box.width, fy = 1 - (e.clientY - box.top) / box.height;
    const k = Math.exp(Math.sign(e.deltaY) * Math.min(Math.abs(e.deltaY), 60) * 0.004);
    zoomBy(k, scalesFor(e.ctrlKey || e.metaKey ? "both" : mode), fx, fy);
  }

  /** Shift-drag, or the middle button, moves the view without changing its size. */
  function grab(e: MouseEvent) {
    const u = plot;
    if (!u || !(e.button === 1 || (e.button === 0 && e.shiftKey))) return;
    e.preventDefault();
    e.stopImmediatePropagation(); // not a zoom box
    const box = u.over.getBoundingClientRect();
    const start = ranges(u), x0 = e.clientX, y0 = e.clientY;
    const move = (m: MouseEvent) => {
      if (plot !== u) return up(); // redrawn with new data while being moved
      const dx = (m.clientX - x0) / box.width, dy = (m.clientY - y0) / box.height;
      setScales(u, Object.fromEntries(start.map(([key, min, max]) => {
        const d = (key === "x" ? -dx : dy) * (max - min);
        return [key, [min + d, max + d] as [number, number]];
      })));
    };
    function up() {
      removeEventListener("mousemove", move);
      removeEventListener("mouseup", up);
    }
    addEventListener("mousemove", move);
    addEventListener("mouseup", up);
  }

  /** What is under the pointer. */
  function point(u: uPlot) {
    const left = u.cursor.left ?? -1, top = u.cursor.top ?? -1;
    if (left < 0 || top < 0) {
      readout = null;
      return;
    }
    const xs = series.map((s) => s.x ?? x);
    if (!xy) {
      // One x column: the sample under the pointer.
      const k = u.cursor.idx;
      readout = k === null || k === undefined ? null : { head: `${xLabel || "x"}: ${num(x[k])}`, values: series.map((s) => num(s.values[k])) };
      return;
    }
    if (forwards) {
      // Curves against the same ascending quantity (time): every curve's value at the cursor's x.
      const xv = u.posToVal(left, "x");
      readout = {
        head: `${xLabel || "x"}: ${num(xv)}`,
        values: series.map((s, i) => {
          const col = xs[i];
          if (!col.length || xv < (col[0] ?? 0) || xv > (col[col.length - 1] ?? 0)) return "";
          return num(s.values[nearest(col, xv)]);
        }),
      };
      return;
    }
    // Otherwise the nearest point of any curve, and when it was.
    let best = -1, bi = -1, bd = Infinity;
    series.forEach((s, i) => {
      if (off.includes(s.label)) return;
      const scale = s.axis === "right" ? "right" : "left", col = xs[i];
      for (let k = 0; k < col.length; k++) {
        const a = col[k], b = s.values[k];
        if (a === null || b === null) continue;
        const d = (u.valToPos(a, "x") - left) ** 2 + (u.valToPos(b, scale) - top) ** 2;
        if (d < bd) {
          bd = d;
          best = i;
          bi = k;
        }
      }
    });
    if (best < 0 || bd > 60 * 60) {
      readout = null;
      return;
    }
    const s = series[best], a = xs[best][bi]!, b = s.values[bi]!;
    const when = s.t?.[bi];
    readout = {
      head: `${xLabel || "x"}: ${num(a)}` + (when === null || when === undefined ? "" : `  at ${num(when)} s`),
      values: series.map((_, i) => (i === best ? num(b) : "")),
      dot: { left: u.valToPos(a, "x") + u.over.offsetLeft, top: u.valToPos(b, s.axis === "right" ? "right" : "left") + u.over.offsetTop, color: s.color },
    };
  }

  /** Shows the whole curve again. */
  function reset() {
    if (!plot) return;
    held = {};
    fresh = true;
    plot.setData(plot.data);
    zoomed = false;
  }

  /** Right-click on a curve's name: hide it, or move it to the other axis. */
  function legendItems(i: number): MenuItem[] {
    const s = series[i], hiddenNow = off.includes(s.label), side = s.axis ?? "left";
    return [
      { header: s.label },
      { label: hiddenNow ? "Show" : "Hide", run: () => toggle(i) },
      { label: "Show only this one", off: series.length < 2 && "The only curve", run: () => series.forEach((x, k) => off.includes(x.label) !== (k !== i) && toggle(k)) },
      ...(pickAxis ? ["-" as const,
        { label: "Left axis", checked: side === "left", run: () => pickAxis!(i, "left") },
        { label: "Right axis", checked: side === "right", run: () => pickAxis!(i, "right") }] : []),
    ];
  }

  /** Hides or shows one curve (a click on its name in the legend). */
  function toggle(i: number) {
    const label = series[i].label, hide = !off.includes(label);
    off = hide ? [...off, label] : off.filter((l) => l !== label);
    plot?.setSeries(i + 1, { show: !hide });
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
    const items = series.filter((s) => !off.includes(s.label)).map((s) => ({ s, w: c.measureText(s.label).width + 30 * r }));
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
        c.strokeStyle = s.color;
        c.lineWidth = 2.5 * r;
        c.setLineDash((s.dash ?? []).map((d) => d * r));
        c.beginPath();
        c.moveTo(px, y);
        c.lineTo(px + 18 * r, y);
        c.stroke();
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
    <span class="hint" title="Drag a box to zoom in. The mouse wheel zooms about the pointer; over one axis it zooms that axis alone. Hold Shift and drag to move the view. Double-click the plot to see it all again, or an axis to fit that axis.">{zoomed ? "Zoomed in · Shift-drag to move" : "Drag or scroll on the plot to zoom"}</span>
    <div class="seg" role="group" aria-label="What zooming changes" title="What a dragged box, the wheel and the + and − buttons change">
      {#each [["both", "X and Y"], ["x", "X only"], ["y", "Y only"]] as [m, label] (m)}
        <button class="pbtn" class:on={mode === m} aria-pressed={mode === m} onclick={() => setMode(m as Mode)}>{label}</button>
      {/each}
    </div>
    <button class="pbtn sq" onclick={() => zoomBy(0.7, scalesFor(mode))} title="Zoom in" aria-label="Zoom in"><ZoomIn size={13} /></button>
    <button class="pbtn sq" onclick={() => zoomBy(1 / 0.7, scalesFor(mode))} disabled={!zoomed} title="Zoom out" aria-label="Zoom out"><ZoomOut size={13} /></button>
    <button class="pbtn" onclick={reset} disabled={!zoomed} title="Show the whole curve again (or double-click the plot)"><Maximize2 size={12} /> Fit</button>
    <button class="pbtn" class:on={ranging} aria-pressed={ranging} onclick={() => (ranging = !ranging)} title="Type the limits of each axis"><SlidersHorizontal size={12} /> Ranges</button>
    <span class="gap"></span>
    <button class="pbtn" onclick={() => save("png")} disabled={busy} title="Save this plot as a picture, as it is shown now"><Download size={13} /> PNG</button>
    <button class="pbtn" onclick={() => save("csv")} disabled={busy} title="Save the plotted numbers as a spreadsheet file"><Download size={13} /> CSV</button>
  </div>
  {#if ranging}
    <div class="ranges">
      {#each Object.entries(limits) as [key, [lo, hi]] (key)}
        <div class="range">
          <span class="rname" title={key === "x" ? xLabel : key === "left" ? leftLabel : rightLabel}>{AXIS_NAME[key] ?? key}</span>
          <input class="rin" aria-label="{AXIS_NAME[key] ?? key} from" value={num(lo, 5).replace(/,/g, "")} onchange={(e) => setLimit(key, 0, e.target as HTMLInputElement)}
            onkeydown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()} />
          <span class="to">to</span>
          <input class="rin" aria-label="{AXIS_NAME[key] ?? key} to" value={num(hi, 5).replace(/,/g, "")} onchange={(e) => setLimit(key, 1, e.target as HTMLInputElement)}
            onkeydown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()} />
          <button class="pbtn" onclick={() => fit(key)} title="Fit this axis to its data">Auto</button>
        </div>
      {/each}
    </div>
  {/if}
{/if}
<div class="wrap">
  <div class="plot" bind:this={host}></div>
  {#if readout?.dot}<span class="dot" style="left:{readout.dot.left}px;top:{readout.dot.top}px;background:{readout.dot.color}"></span>{/if}
</div>
{#if x.length > 0}
  <div class="legend">
    <span class="head">{readout?.head ?? ""}</span>
    {#each series as s, i (i)}
      <button class="item" class:off={off.includes(s.label)} onclick={() => toggle(i)} aria-pressed={!off.includes(s.label)} use:context={() => legendItems(i)}
        title={(off.includes(s.label) ? "Show this curve" : "Hide this curve") + (pickAxis ? ". Right-click to choose its axis." : "")}>
        <svg width="20" height="8" aria-hidden="true"><line x1="0" y1="4" x2="20" y2="4" stroke={s.color} stroke-width="2.5" stroke-dasharray={(s.dash ?? []).join(" ")} /></svg>
        {s.label}{#if readout?.values[i] && !off.includes(s.label)}: <b>{readout.values[i]}</b>{/if}
      </button>
    {/each}
  </div>
{/if}

<style>
  .plot {
    width: 100%;
    min-width: 0;
  }
  .plot :global(.u-select) {
    background: color-mix(in srgb, var(--select) 15%, transparent);
    border: 1px solid color-mix(in srgb, var(--select) 60%, transparent);
  }
  .wrap {
    position: relative;
  }
  .dot {
    position: absolute;
    width: 9px;
    height: 9px;
    margin: -4.5px 0 0 -4.5px;
    border-radius: 50%;
    border: 1.5px solid var(--surface);
    pointer-events: none;
  }
  .legend {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 2px 14px;
    font-size: 12px;
    color: var(--ink-2);
    padding: 2px 0 4px;
    min-height: 20px;
  }
  .legend .head {
    flex: 0 0 100%;
    text-align: center;
    min-height: 16px;
    font-variant-numeric: tabular-nums;
  }
  .legend .item {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    padding: 0;
    border: 0;
    background: none;
    font: inherit;
    color: inherit;
    cursor: pointer;
    font-variant-numeric: tabular-nums;
  }
  .legend .item.off {
    opacity: 0.4;
    text-decoration: line-through;
  }
  .legend b {
    font-weight: 600;
    color: var(--ink);
  }
  .bar {
    display: flex;
    flex-wrap: wrap;
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
  .pbtn.on {
    color: var(--ink);
    background: color-mix(in srgb, var(--select) 16%, transparent);
    border-color: color-mix(in srgb, var(--select) 55%, transparent);
  }
  .pbtn.sq {
    padding: 0 5px;
  }
  .seg {
    display: inline-flex;
  }
  .seg .pbtn {
    border-radius: 0;
    margin-left: -1px;
  }
  .seg .pbtn:first-child {
    border-radius: 5px 0 0 5px;
    margin-left: 0;
  }
  .seg .pbtn:last-child {
    border-radius: 0 5px 5px 0;
  }
  .gap {
    width: 8px;
  }
  .ranges {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 18px;
    padding: 4px 0 2px;
    font-size: 11.5px;
    color: var(--ink-2);
  }
  .range {
    display: inline-flex;
    align-items: center;
    gap: 5px;
  }
  .rname {
    min-width: 58px;
  }
  .rin {
    width: 78px;
    height: 22px;
    padding: 0 5px;
    font: inherit;
    font-variant-numeric: tabular-nums;
    color: var(--ink);
    background: var(--surface);
    border: 1px solid var(--line, color-mix(in srgb, var(--ink-3) 35%, transparent));
    border-radius: 5px;
  }
  .to {
    color: var(--ink-3);
  }
  .pbtn:disabled {
    opacity: 0.5;
    cursor: default;
  }
</style>
