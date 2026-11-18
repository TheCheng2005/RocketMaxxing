<script lang="ts">
  import { TriangleAlert, Gauge, Crosshair, Target, Weight, Ruler, Mountain, Zap, Activity, ArrowUpFromLine } from "@lucide/svelte";
  import { app } from "../state.svelte";
  import { fmt, num } from "../units.svelte";
  import { KINDS } from "../issues/issues.svelte";
  import { context, copyText, type MenuItem } from "../menu/menu.svelte";
  import { toast } from "../state.svelte";
  import { analysis } from "../analysis/store.svelte";

  // Right-click: copy the numbers (for notes or a chat), or go where each is worked out in depth.
  function summary(): string {
    const lines = [
      `${app.design?.name ?? "Design"}`,
      `Stability ${margin === null ? "–" : num(margin, 3) + " cal"}${st.floor ? ` (min ${num(st.floor, 3)})` : ""}`,
      `CG / CP ${fmt(st.cg, "LENGTH")} / ${fmt(st.cp, "LENGTH")}`,
      `Mass ${fmt(st.mass, "MASS")} (burnout ${fmt(st.massBurnout, "MASS")})`,
      `Length / diameter ${fmt(st.length, "LENGTH")} / ${fmt(st.diameter, "LENGTH")}`,
    ];
    if (f && !f.noMotors) lines.push(`Apogee ${fmt(f.apogee, "DISTANCE")}`, `Max speed ${fmt(f.maxVelocity, "VELOCITY")}`, `Max accel. ${fmt(f.maxAcceleration, "ACCELERATION")}`, `Rail exit ${fmt(f.railExitVelocity, "VELOCITY")}`);
    return lines.join("\n");
  }
  const to = (tool: string) => () => {
    analysis.selected = tool;
    app.page = "analysis";
  };
  function items(): MenuItem[] {
    return [
      { header: "Summary" },
      { label: "Copy the numbers", run: () => copyText(summary()).then((ok) => ok && toast("Copied the summary")) },
      "-",
      { label: "Stability and drag vs Mach", run: to("aero_analysis") },
      { label: "Nose ballast for stability", run: to("ballast") },
      { label: "Mass budget", run: to("mass_budget") },
      { label: "Simulations and plots", run: () => (app.page = "flights") },
      { label: "Choose a motor", run: () => (app.page = "motors") },
      { label: "Targets", run: () => (app.page = "targets") },
    ];
  }

  let st = $derived(app.design?.stability ?? {});
  let f = $derived(app.flight);
  let margin = $derived(st.margin ?? null);
  // Against the rule set's minimum (Launch Canada: 1.5 cal); 1 cal when the rule set has none.
  let floor = $derived(st.floor ?? 1);
  let level = $derived(margin === null ? "" : margin < floor ? "bad" : margin < floor + 0.5 ? "warn" : "good");
  // The flight's notes with whose problem each is (physics, output); the stability warnings are physics too.
  let notes = $derived([
    ...app.flightIssues.map((i) => ({ kind: i.kind, severity: i.severity, text: i.text })),
    ...((st.warnings ?? []) as string[]).map((text) => ({ kind: "physics" as const, severity: "warning", text })),
  ]);
</script>

<div class="sum" use:context={items}>
  {@render tile(Gauge, "Stability", margin === null ? "–" : num(margin, 3) + " cal", st.floor ? `min ${num(st.floor, 3)}` : "", level, "Static margin at Mach 0.3, launch mass")}
  {@render tile(Crosshair, "CG", fmt(st.cg, "LENGTH"), "from the nose tip", "", "Centre of gravity at launch mass, from the nose tip")}
  {@render tile(Target, "CP", fmt(st.cp, "LENGTH"), "from the nose tip", "", "Centre of pressure at Mach 0.3, from the nose tip")}
  {@render tile(Weight, "Mass", fmt(st.mass, "MASS"), `${fmt(st.massBurnout, "MASS")} empty`)}
  {@render tile(Ruler, "Length / Ø", `${fmt(st.length, "LENGTH")} / ${fmt(st.diameter, "LENGTH")}`)}
  {#if f?.noMotors}
    {@render tile(Mountain, "Flight", "No motor", "choose one on Motors", "none")}
  {:else}
    {@render tile(Mountain, "Apogee", fmt(f?.apogee, "DISTANCE"), app.design?.aeroTable === "RocketMaxxing high-speed model" ? "high-speed model" : app.design?.aeroTable ? "RASAero drag" : "", "", app.design?.aeroTable === "RocketMaxxing high-speed model" ? "Flown on the high-speed drag model; High-speed drag below shows it beside OpenRocket's" : app.design?.aeroTable ? "Flown on the imported RASAero table's drag; High-speed drag below shows both" : "Quick simulation of the active configuration", true)}
    {@render tile(Zap, "Max speed", fmt(f?.maxVelocity, "VELOCITY"), f?.maxMach !== undefined ? `Mach ${f.maxMach.toFixed(2)}` : "", "", "", true)}
    {@render tile(Activity, "Max accel.", fmt(f?.maxAcceleration, "ACCELERATION"), "", "", "", true)}
    {@render tile(ArrowUpFromLine, "Rail exit", fmt(f?.railExitVelocity, "VELOCITY"), "", "", "", true)}
  {/if}
</div>
{#if notes.length}
  <ul class="notes">
    {#each notes as n}<li class="k-{n.kind} {n.severity}"><TriangleAlert size={13} /> <b>{KINDS[n.kind].label}</b> {n.text}</li>{/each}
  </ul>
{/if}

{#snippet tile(I: typeof Gauge, label: string, value: string, sub = "", cls = "", title = "", flight = false)}
  <div class="tile" class:stale={flight && app.flying} title={title || undefined}>
    <div class="tl"><I size={13} /> {label}</div>
    <div class="tv {cls}"><span class="main">{value}</span>{#if sub}<small>{sub}</small>{/if}</div>
  </div>
{/snippet}

<style>
  .sum {
    display: grid;
    /* Wider tiles for larger text, so the numbers keep their room. */
    /* Three to a row: stability and the two centres, then mass, size and the flight (its numbers fill a third row). */
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
    padding: 12px;
  }
  .tile {
    min-width: 0;
    padding: 8px 12px 9px;
    border: 1px solid var(--line);
    border-radius: 8px;
    background: var(--surface-2);
  }
  .tile.stale {
    opacity: 0.55;
  }
  .tl {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: calc(11.5px * var(--text));
    color: var(--ink-3);
  }
  /* The number stays whole; the note after it moves under it when the tile is narrow. */
  .tv {
    display: flex;
    flex-direction: column;
    row-gap: 1px;
    margin-top: 3px;
    font-size: calc(16px * var(--text));
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }
  .tv .main {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .tv small {
    min-width: 0;
    font-size: calc(11.5px * var(--text));
    font-weight: 400;
    color: var(--ink-3);
  }
  .tv.good {
    color: var(--good);
  }
  .tv.warn {
    color: var(--warn);
  }
  .tv.bad {
    color: var(--bad);
  }
  .tv.none {
    font-weight: 500;
    color: var(--ink-3);
  }
  .ts {
    font-size: calc(11.5px * var(--text));
    color: var(--ink-3);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .notes {
    list-style: none;
    margin: 0;
    padding: 0 12px 12px;
    font-size: calc(12px * var(--text));
    color: var(--warn);
  }
  .notes li {
    display: flex;
    align-items: baseline;
    gap: 5px;
  }
  .notes li :global(svg) {
    flex: none;
    align-self: center;
  }
  .notes li.error {
    color: var(--bad);
  }
  .notes b {
    flex: none;
    font-size: calc(10.5px * var(--text));
    letter-spacing: 0.04em;
    text-transform: uppercase;
  }
  @media (max-width: 900px) {
    .sum {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .flight {
      border-left: 0;
      padding-left: 0;
      border-top: 1px solid var(--line);
      padding-top: 4px;
      margin-top: 4px;
    }
  }
</style>
