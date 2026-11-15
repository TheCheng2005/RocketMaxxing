<script module lang="ts">
  /** What the rocket says while the engine works. */
  export const WORDS = [
    "Igniting", "Counting down", "Clearing the rail", "Going transonic", "Chasing apogee", "Dodging geese",
    "Consulting Barrowman", "Nudging the CP aft", "Weighing the nose cone", "Folding the parachute", "Sanding the fins",
    "Torquing the shear pins", "Arming the altimeters", "Measuring black powder", "Asking OpenRocket nicely",
    "Integrating thrust", "Outrunning the wind", "Fluttering (not the fins)", "Tracking the drift",
    "Waiting for the range safety officer", "Calibrating the barometer", "Coasting", "Popping the drogue",
    "Inflating the main", "Checking the static margin", "Waiting for the epoxy", "Hunting for the GPS signal",
    "Plotting the trajectory", "Burning propellant", "Defying gravity", "Climbing past the clouds", "Chasing Mach 1",
    "Running the numbers", "Tightening the U-bolts", "Greasing the rail buttons", "Laying up carbon",
    "Converting feet to metres", "Squinting at the sky", "Waiting for the wind to drop", "Finding the fin can",
    "Re-reading the DTEG", "Bribing the motor vendor", "Spotting the landing zone", "Asking the launch director",
  ];

  type Sky = "day" | "dusk" | "space";
  /** What can drift past: its name (for the tooltip), the sky it belongs in, and how high it flies (0 low, 1 high). */
  export const THINGS: { id: string; name: string; sky: Sky; y: [number, number] }[] = [
    { id: "cloud", name: "a cloud", sky: "day", y: [0.15, 0.8] },
    { id: "geese", name: "some geese", sky: "day", y: [0.2, 0.7] },
    { id: "plane", name: "an airliner", sky: "day", y: [0.15, 0.5] },
    { id: "balloon", name: "a hot-air balloon", sky: "day", y: [0.3, 0.7] },
    { id: "kite", name: "a kite", sky: "day", y: [0.4, 0.8] },
    { id: "drone", name: "a drone", sky: "day", y: [0.3, 0.8] },
    { id: "rocket", name: "another team's rocket", sky: "dusk", y: [0.2, 0.8] },
    { id: "moon", name: "the Moon", sky: "dusk", y: [0.15, 0.4] },
    { id: "satellite", name: "a satellite", sky: "space", y: [0.2, 0.7] },
    { id: "saturn", name: "Saturn", sky: "space", y: [0.2, 0.6] },
    { id: "ufo", name: "a UFO", sky: "space", y: [0.2, 0.5] },
    { id: "comet", name: "a comet", sky: "space", y: [0.15, 0.6] },
    { id: "station", name: "a space station", sky: "space", y: [0.2, 0.6] },
    { id: "astronaut", name: "an astronaut", sky: "space", y: [0.3, 0.7] },
  ];

  let instances = 0;

  function pick<T>(list: T[], not?: T): T {
    let x = list[Math.floor(Math.random() * list.length)];
    if (list.length > 1 && x === not) x = list[(list.indexOf(x) + 1) % list.length];
    return x;
  }
</script>

<script lang="ts">
  import { onMount } from "svelte";

  // The app's loading indicator: a small rocket flying past whatever happens to be up there, and a word about what it
  // is doing. Big for pages and long jobs, small for a line of text, tiny for the top bar.
  let { size = "big", text = "" }: { size?: "big" | "small" | "tiny"; text?: string } = $props();

  const clip = `rl-clip-${++instances}`;
  const W = 240;
  const H = 92;
  let word = $state(pick(WORDS));
  let thing = $state(pick(THINGS));
  let pass = $state(0);
  let y = $state(0.5);
  let scale = $state(1);
  let sky = $derived(thing.sky);

  function next() {
    thing = pick(THINGS, thing);
    y = thing.y[0] + Math.random() * (thing.y[1] - thing.y[0]);
    scale = 1.2 + Math.random() * 0.5;
    pass++;
  }

  onMount(() => {
    next();
    const t = setInterval(() => (word = pick(WORDS, word)), 2600);
    return () => clearInterval(t);
  });
</script>

{#snippet rocket()}
  <!-- Pointing right, its tail at 0,0: body, nose, fins, window, and a flame that flickers. -->
  <g class="flame">
    <path d="M0,-3.5 L-12,0 L0,3.5 Z" fill="#ffb02e" />
    <path d="M0,-2 L-7,0 L0,2 Z" fill="#fff3c4" />
  </g>
  <path d="M2,-5 L-3,-10 L8,-5 Z M2,5 L-3,10 L8,5 Z" fill="#e8590c" />
  <rect x="0" y="-5" width="24" height="10" rx="2" fill="#fff" stroke="#c9ced6" stroke-width="0.8" />
  <path d="M24,-5 Q36,0 24,5 Z" fill="#e8590c" />
  <circle cx="15" cy="0" r="2.3" fill="#7fb2ff" stroke="#c9ced6" stroke-width="0.6" />
{/snippet}

{#snippet object(id: string)}
  {#if id === "cloud"}
    <g fill="#fff" opacity="0.95"><circle cx="-10" cy="2" r="7" /><circle cx="0" cy="-3" r="10" /><circle cx="11" cy="2" r="7" /><rect x="-17" y="2" width="35" height="7" rx="3.5" /></g>
  {:else if id === "geese"}
    <g fill="none" stroke="#334" stroke-width="1.6" stroke-linecap="round">
      <path class="flap" d="M-12,0 l5,-4 l5,4" /><path class="flap late" d="M0,-8 l5,-4 l5,4" /><path class="flap" d="M6,6 l5,-4 l5,4" />
    </g>
  {:else if id === "plane"}
    <g fill="#f4f6f9" stroke="#8a93a3" stroke-width="0.8">
      <path d="M-20,-1 Q-22,-4 -16,-4 L14,-4 Q22,-3 22,0 Q22,3 14,3 L-16,3 Q-22,3 -20,-1 Z" />
      <path d="M-4,-1 L4,-1 L-6,-12 L-10,-12 Z" /><path d="M-4,1 L4,1 L-6,11 L-10,11 Z" /><path d="M-18,-3 L-14,-3 L-19,-10 L-21,-10 Z" />
    </g>
    <g fill="#5b8def"><circle cx="12" cy="-1" r="1" /><circle cx="8" cy="-1" r="1" /><circle cx="4" cy="-1" r="1" /><circle cx="0" cy="-1" r="1" /></g>
  {:else if id === "balloon"}
    <path d="M0,-16 C12,-16 14,-2 4,6 L-4,6 C-14,-2 -12,-16 0,-16 Z" fill="#e8590c" />
    <path d="M0,-16 C5,-16 6,-2 2,6 L-2,6 C-6,-2 -5,-16 0,-16 Z" fill="#ffd38a" />
    <path d="M-4,6 L-3,11 M4,6 L3,11" stroke="#7a5230" stroke-width="0.8" /><rect x="-3.5" y="11" width="7" height="5" fill="#a0713f" />
  {:else if id === "kite"}
    <g class="sway"><path d="M0,-12 L8,0 L0,12 L-8,0 Z" fill="#d63384" /><path d="M0,-12 L0,12 M-8,0 L8,0" stroke="#fff" stroke-width="0.8" />
      <path d="M0,12 q4,5 0,9 q-4,5 0,9" fill="none" stroke="#555" stroke-width="0.8" /></g>
  {:else if id === "drone"}
    <g stroke="#445" stroke-width="1.2"><path d="M-10,-4 L10,4 M-10,4 L10,-4" /></g>
    <rect x="-4" y="-2.5" width="8" height="5" rx="1.5" fill="#445" />
    <g fill="#9aa4b6" class="spin"><ellipse cx="-10" cy="-5" rx="5" ry="1" /><ellipse cx="10" cy="-5" rx="5" ry="1" /><ellipse cx="-10" cy="3" rx="5" ry="1" /><ellipse cx="10" cy="3" rx="5" ry="1" /></g>
  {:else if id === "rocket"}
    <g transform="rotate(-25) scale(0.8)"><path d="M0,-3 L-14,0 L0,3 Z" fill="#7fe0ff" /><rect x="0" y="-5" width="22" height="10" rx="2" fill="#2f6fdf" /><path d="M22,-5 Q33,0 22,5 Z" fill="#ffd400" /><path d="M2,-5 L-3,-10 L8,-5 Z M2,5 L-3,10 L8,5 Z" fill="#ffd400" /></g>
  {:else if id === "moon"}
    <path d="M6,-14 A14,14 0 1,0 6,14 A11,11 0 1,1 6,-14 Z" fill="#f5f0d0" />
  {:else if id === "satellite"}
    <g class="tumble"><rect x="-5" y="-5" width="10" height="10" fill="#d9b44a" /><rect x="-22" y="-3" width="15" height="6" fill="#3a5fb0" stroke="#9fb4e0" stroke-width="0.5" />
      <rect x="7" y="-3" width="15" height="6" fill="#3a5fb0" stroke="#9fb4e0" stroke-width="0.5" /><path d="M0,-5 L0,-11" stroke="#ccc" stroke-width="1" /><circle cx="0" cy="-12" r="1.5" fill="#ccc" /></g>
  {:else if id === "saturn"}
    <circle r="10" fill="#e9c27a" /><path d="M-10,-2 Q0,2 10,-2" stroke="#c99a4a" stroke-width="1.5" fill="none" />
    <ellipse rx="19" ry="5" fill="none" stroke="#f2dfb4" stroke-width="2.2" transform="rotate(-15)" />
  {:else if id === "ufo"}
    <g class="hover"><path d="M-6,-3 Q0,-13 6,-3 Z" fill="#9fe7c9" opacity="0.9" /><ellipse cx="0" cy="-1" rx="16" ry="5" fill="#9aa4b6" /><ellipse cx="0" cy="-2" rx="16" ry="2.5" fill="#c9d0dc" />
      <g fill="#ffe066"><circle cx="-9" cy="0" r="1.2" /><circle cx="0" cy="1" r="1.2" /><circle cx="9" cy="0" r="1.2" /></g></g>
  {:else if id === "comet"}
    <path d="M0,-4 L34,-10 L34,10 L0,4 Z" fill="url(#rl-tail)" /><circle r="5" fill="#fff" /><circle r="3" fill="#cfe8ff" />
  {:else if id === "station"}
    <rect x="-24" y="-1" width="48" height="2" fill="#bbb" />
    <g fill="#3a5fb0" stroke="#9fb4e0" stroke-width="0.4"><rect x="-24" y="-12" width="8" height="10" /><rect x="-24" y="2" width="8" height="10" /><rect x="16" y="-12" width="8" height="10" /><rect x="16" y="2" width="8" height="10" /></g>
    <rect x="-6" y="-4" width="12" height="8" rx="2" fill="#e8e8e8" />
  {:else if id === "astronaut"}
    <g class="tumble"><rect x="-6" y="-4" width="12" height="13" rx="4" fill="#f4f4f4" stroke="#aaa" stroke-width="0.6" /><circle cy="-8" r="6" fill="#f4f4f4" stroke="#aaa" stroke-width="0.6" />
      <rect x="-3.5" y="-10" width="7" height="4" rx="2" fill="#ffb02e" /><rect x="-10" y="-2" width="4" height="8" rx="2" fill="#f4f4f4" stroke="#aaa" stroke-width="0.6" /><rect x="6" y="-2" width="4" height="8" rx="2" fill="#f4f4f4" stroke="#aaa" stroke-width="0.6" /></g>
  {/if}
{/snippet}

{#if size === "tiny"}
  <svg class="tiny" viewBox="-14 -12 52 24" width="26" height="12" aria-hidden="true"><g class="bob">{@render rocket()}</g></svg>
{:else if size === "small"}
  <span class="small" role="status">
    <svg viewBox="-14 -12 52 24" width="30" height="14" aria-hidden="true"><g class="bob">{@render rocket()}</g></svg>
    <span class="word">{text || word}…</span>
  </span>
{:else}
  <div class="big" role="status">
    <svg class="scene {sky}" viewBox="0 0 {W} {H}" width={W} height={H} aria-hidden="true">
      <defs>
        <clipPath id={clip}><rect width={W} height={H} rx="12" /></clipPath>
        <linearGradient id="rl-tail" x1="0" x2="1"><stop offset="0" stop-color="#cfe8ff" stop-opacity="0.9" /><stop offset="1" stop-color="#cfe8ff" stop-opacity="0" /></linearGradient>
      </defs>
      <g clip-path="url(#{clip})">
      <rect class="sky" width={W} height={H} rx="12" />
      {#if sky !== "day"}
        <g class="stars" fill="#fff">
          {#each [[20, 12], [60, 70], [110, 18], [150, 60], [200, 25], [225, 75], [35, 45], [180, 40]] as [sx, sy], i (i)}<circle cx={sx} cy={sy} r={i % 3 === 0 ? 1.2 : 0.7} />{/each}
        </g>
      {/if}
      <g class="lines" stroke-linecap="round">
        <line x1="0" y1="20" x2="18" y2="20" /><line x1="0" y1="52" x2="26" y2="52" /><line x1="0" y1="78" x2="14" y2="78" />
      </g>
      {#key pass}
        <g class="pass" style="--y: {12 + y * (H - 24)}px; --s: {scale}" onanimationend={next}>
          <g class="thing"><title>{thing.name}</title>{@render object(thing.id)}</g>
        </g>
      {/key}
      <g class="smoke">
        {#each [0, 1, 2, 3] as i (i)}<circle cx="64" cy={H / 2 + 7} r="3.5" style="animation-delay: {i * 0.35}s" />{/each}
      </g>
      <g transform="translate(84 {H / 2 + 4}) rotate(-8) scale(1.5)"><g class="bob">{@render rocket()}</g></g>
      </g>
    </svg>
    <div class="word">{word}…</div>
    {#if text}<div class="text">{text}</div>{/if}
  </div>
{/if}

<style>
  .big {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    padding: 12px;
  }
  .big .word {
    font-size: calc(13.5px * var(--text));
    font-weight: 600;
    color: var(--ink-2);
  }
  .text {
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
    text-align: center;
    max-width: 360px;
  }
  .scene {
    display: block;
    max-width: 100%;
    height: auto;
  }
  .sky {
    transition: fill 1.2s;
  }
  .day .sky {
    fill: #d8e9ff;
  }
  .dusk .sky {
    fill: #3b3f7a;
  }
  .space .sky {
    fill: #0d1433;
  }
  .lines line {
    stroke: rgb(255 255 255 / 70%);
    stroke-width: 1.5;
    animation: streak 0.9s linear infinite;
  }
  .lines line:nth-child(2) {
    animation-duration: 0.7s;
  }
  .lines line:nth-child(3) {
    animation-duration: 1.1s;
  }
  .pass {
    animation: drift 2.8s linear forwards;
  }
  .thing {
    transform: translateY(var(--y)) scale(var(--s));
    transform-box: fill-box;
  }
  .smoke circle {
    fill: rgb(200 205 215 / 80%);
    animation: puff 1.4s ease-out infinite;
  }
  .bob {
    animation: bob 1.3s ease-in-out infinite;
  }
  .flame {
    transform-origin: 0 0;
    animation: flicker 0.12s steps(2) infinite alternate;
  }
  .flap {
    transform-box: fill-box;
    transform-origin: center;
    animation: flap 0.45s ease-in-out infinite alternate;
  }
  .flap.late {
    animation-delay: 0.2s;
  }
  .spin {
    animation: flicker 0.08s steps(2) infinite alternate;
  }
  .tumble {
    transform-box: fill-box;
    transform-origin: center;
    animation: tumble 6s linear infinite;
  }
  .hover,
  .sway {
    animation: bob 0.9s ease-in-out infinite;
  }
  .small {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: calc(12.5px * var(--text));
    color: var(--ink-3);
    white-space: nowrap;
  }
  .tiny {
    display: block;
  }
  @keyframes drift {
    from {
      transform: translateX(270px);
    }
    to {
      transform: translateX(-40px);
    }
  }
  @keyframes streak {
    from {
      transform: translateX(250px);
    }
    to {
      transform: translateX(-30px);
    }
  }
  @keyframes puff {
    from {
      transform: translate(0, 0) scale(0.6);
      opacity: 0.9;
    }
    to {
      transform: translate(-60px, 0) scale(2.4);
      opacity: 0;
    }
  }
  @keyframes bob {
    50% {
      transform: translateY(-2.5px);
    }
  }
  @keyframes flicker {
    from {
      transform: scaleX(0.75);
    }
    to {
      transform: scaleX(1.25);
    }
  }
  @keyframes flap {
    to {
      transform: scaleY(-0.6);
    }
  }
  @keyframes tumble {
    to {
      transform: rotate(360deg);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .pass {
      animation-duration: 8s;
    }
    .lines line,
    .smoke circle,
    .bob,
    .flame,
    .flap,
    .spin,
    .tumble,
    .hover,
    .sway {
      animation: none;
    }
  }
</style>
