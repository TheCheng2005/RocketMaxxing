<script lang="ts">
  // Drawings of each kind of part, side-on with the nose to the left as in the rocket view. Colour by family:
  // airframe, fins, outside fittings, inside structure, recovery, mass, assemblies.
  let { type, size = 40 }: { type: string; size?: number } = $props();

  const FAMILY: Record<string, string> = {
    Rocket: "assembly", AxialStage: "assembly", ParallelStage: "assembly", PodSet: "assembly",
    NoseCone: "body", BodyTube: "body", Transition: "body",
    TrapezoidFinSet: "fins", EllipticalFinSet: "fins", FreeformFinSet: "fins", TubeFinSet: "fins",
    LaunchLug: "outside", RailButton: "outside",
    InnerTube: "inside", TubeCoupler: "inside", CenteringRing: "inside", Bulkhead: "inside", EngineBlock: "inside",
    Parachute: "recovery", Streamer: "recovery", ShockCord: "recovery",
    MassComponent: "mass", RecoveryHardware: "recovery",
  };
  let family = $derived(FAMILY[type] ?? "body");
</script>

<svg class="ci {family}" width={size} height={size} viewBox="0 0 48 48" aria-hidden="true" fill="none" stroke-linecap="round" stroke-linejoin="round">
  {#if type === "Rocket"}
    <path class="fill" d="M6 24c4-6 10-8 16-8h14l6 8-6 8H22c-6 0-12-2-16-8z" />
    <path class="line" d="M6 24c4-6 10-8 16-8h14l6 8-6 8H22c-6 0-12-2-16-8z" />
    <path class="line" d="M36 16l6-6v8M36 32l6 6v-8M24 16v16" />
  {:else if type === "NoseCone"}
    <path class="fill" d="M4 24C10 13 22 12 42 12v24C22 36 10 35 4 24z" />
    <path class="line" d="M4 24C10 13 22 12 42 12v24C22 36 10 35 4 24z" />
  {:else if type === "BodyTube"}
    <rect class="fill" x="4" y="13" width="40" height="22" rx="1.5" />
    <rect class="line" x="4" y="13" width="40" height="22" rx="1.5" />
    <path class="thin" d="M4 17h40" />
  {:else if type === "Transition"}
    <path class="fill" d="M4 15h12l26-5v28l-26-5H4z" />
    <path class="line" d="M4 15h12l26-5v28l-26-5H4z" />
  {:else if type === "TrapezoidFinSet"}
    <path class="thin" d="M2 34h44" />
    <path class="fill" d="M10 34l12-22h12l8 22z" />
    <path class="line" d="M10 34l12-22h12l8 22z" />
  {:else if type === "EllipticalFinSet"}
    <path class="thin" d="M2 34h44" />
    <path class="fill" d="M8 34c0-14 7-22 16-22s16 8 16 22z" />
    <path class="line" d="M8 34c0-14 7-22 16-22s16 8 16 22z" />
  {:else if type === "FreeformFinSet"}
    <path class="thin" d="M2 34h44" />
    <path class="fill" d="M8 34l6-14 10-8 6 10 12-4-2 16z" />
    <path class="line" d="M8 34l6-14 10-8 6 10 12-4-2 16z" />
  {:else if type === "TubeFinSet"}
    <circle class="fill" cx="24" cy="24" r="7" />
    <circle class="line" cx="24" cy="24" r="7" />
    <circle class="line" cx="24" cy="10" r="5" /><circle class="line" cx="36" cy="31" r="5" /><circle class="line" cx="12" cy="31" r="5" />
  {:else if type === "LaunchLug"}
    <path class="thin" d="M2 32h44" />
    <rect class="fill" x="10" y="21" width="28" height="9" rx="2" />
    <rect class="line" x="10" y="21" width="28" height="9" rx="2" />
  {:else if type === "RailButton"}
    <path class="thin" d="M2 36h44" />
    <path class="fill" d="M14 14h20v6h-6v6h8v6H12v-6h8v-6h-6z" />
    <path class="line" d="M14 14h20v6h-6v6h8v6H12v-6h8v-6h-6z" />
  {:else if type === "InnerTube"}
    <rect class="thin dash" x="3" y="9" width="42" height="30" rx="1.5" />
    <rect class="fill" x="10" y="17" width="34" height="14" rx="1.5" />
    <rect class="line" x="10" y="17" width="34" height="14" rx="1.5" />
  {:else if type === "TubeCoupler"}
    <rect class="thin dash" x="3" y="11" width="42" height="26" />
    <path class="thin" d="M24 9v30" />
    <rect class="fill" x="12" y="14" width="24" height="20" rx="1.5" />
    <rect class="line" x="12" y="14" width="24" height="20" rx="1.5" />
  {:else if type === "CenteringRing"}
    <circle class="fill" cx="24" cy="24" r="18" />
    <circle class="line" cx="24" cy="24" r="18" />
    <circle class="hole" cx="24" cy="24" r="8" />
    <circle class="line" cx="24" cy="24" r="8" />
  {:else if type === "Bulkhead"}
    <circle class="fill" cx="24" cy="24" r="18" />
    <circle class="line" cx="24" cy="24" r="18" />
    <path class="line" d="M24 16v-6M21 10h6" />
  {:else if type === "EngineBlock"}
    <circle class="fill" cx="24" cy="24" r="16" />
    <circle class="line" cx="24" cy="24" r="16" />
    <circle class="hole" cx="24" cy="24" r="10" />
    <circle class="line" cx="24" cy="24" r="10" />
  {:else if type === "Parachute"}
    <path class="fill" d="M5 22C5 11 14 5 24 5s19 6 19 17c-3-2-6-2-9 0-3-2-7-2-10 0-3-2-7-2-10 0-3-2-6-2-9 0z" />
    <path class="line" d="M5 22C5 11 14 5 24 5s19 6 19 17c-3-2-6-2-9 0-3-2-7-2-10 0-3-2-7-2-10 0-3-2-6-2-9 0z" />
    <path class="thin" d="M5 22l19 18 19-18M14 22l10 18 10-18" />
  {:else if type === "Streamer"}
    <path class="fill" d="M6 8h10c8 6 2 14 10 20s10 4 16 12H32c-6-8-8-6-16-12S16 14 6 8z" />
    <path class="line" d="M6 8h10c8 6 2 14 10 20s10 4 16 12H32c-6-8-8-6-16-12S16 14 6 8z" />
  {:else if type === "ShockCord"}
    <path class="line" d="M4 30c4-12 8-12 10 0s6 12 10 0 6-12 10 0 6 12 10 0" />
    <circle class="line" cx="4" cy="30" r="2" /><circle class="line" cx="44" cy="30" r="2" />
  {:else if type === "RecoveryHardware"}
    <!-- connectors: an eyebolt through a bulkhead, a quick link hooked into its eye -->
    <path class="line" d="M6 14v20M6 24h14" />
    <path class="thin" d="M3 18h6M3 30h6" />
    <circle class="line" cx="25" cy="24" r="5" />
    <rect class="line" x="27" y="17" width="17" height="14" rx="7" />
  {:else if type === "MassComponent"}
    <path class="fill" d="M10 18h28l4 22H6z" />
    <path class="line" d="M10 18h28l4 22H6z" />
    <path class="line" d="M18 18a6 6 0 1 1 12 0" />
  {:else if type === "AxialStage"}
    <rect class="fill" x="4" y="14" width="18" height="20" rx="1.5" />
    <rect class="line" x="4" y="14" width="18" height="20" rx="1.5" />
    <rect class="line" x="26" y="14" width="18" height="20" rx="1.5" />
    <path class="thin" d="M22 24h4" />
  {:else if type === "ParallelStage"}
    <rect class="fill" x="6" y="18" width="36" height="12" rx="1.5" />
    <rect class="line" x="6" y="18" width="36" height="12" rx="1.5" />
    <rect class="line" x="14" y="5" width="24" height="9" rx="1.5" /><rect class="line" x="14" y="34" width="24" height="9" rx="1.5" />
  {:else if type === "PodSet"}
    <rect class="fill" x="4" y="18" width="40" height="12" rx="1.5" />
    <rect class="line" x="4" y="18" width="40" height="12" rx="1.5" />
    <path class="line" d="M16 14h16l4-6H20z" /><path class="line" d="M16 34h16l4 6H20z" />
  {:else}
    <circle class="fill" cx="24" cy="24" r="16" />
    <circle class="line" cx="24" cy="24" r="16" />
  {/if}
</svg>

<style>
  .ci {
    flex: none;
    display: block;
  }
  .line {
    stroke: var(--icon-ink);
    stroke-width: 2.2;
  }
  .thin {
    stroke: var(--icon-ink);
    stroke-width: 1.4;
    opacity: 0.55;
  }
  .dash {
    stroke-dasharray: 3 3;
  }
  .hole {
    fill: var(--surface);
  }
  .fill {
    stroke: none;
  }
  .body .fill { fill: var(--fam-body); }
  .fins .fill { fill: var(--fam-fins); }
  .outside .fill { fill: var(--fam-outside); }
  .inside .fill { fill: var(--fam-inside); }
  .recovery .fill { fill: var(--fam-recovery); }
  .mass .fill { fill: var(--fam-mass); }
  .assembly .fill { fill: var(--fam-assembly); }
</style>
