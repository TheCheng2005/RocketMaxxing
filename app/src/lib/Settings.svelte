<script lang="ts">
  import { X, Ruler, Palette, ShieldCheck, Bot, FolderOpen, ChevronDown } from "@lucide/svelte";
  import { isOpen, toggle, setOpen } from "./panels.svelte";
  import { app, setSystem, autoCheck, setAutoCheck } from "./state.svelte";
  import { units, choices, choose, unitOf, pretty, type Dim } from "./units.svelte";
  import { prefs, setTheme, setScheme, SCHEMES, SIZES, setSize, setDensity, setMotion, setDigits, setMark, resetLayout, resetTours,
    type Theme, type Scheme, type Density, type Motion, type Mark } from "./prefs.svelte";
  import { call } from "./api";
  import { run, toast } from "./state.svelte";
  import { onMount } from "svelte";
  import { assistant, configure } from "./assistant/assistant.svelte";
  import AssistantSetup from "./assistant/AssistantSetup.svelte";
  import { updates, checkNow, checkEnabled, setCheckEnabled } from "./updates.svelte";

  let { close, section = "" }: { close: () => void; section?: string } = $props();
  let content = $state<HTMLDivElement>();

  // Opened from the search at a section: open that section and show it.
  const FOLDED_FIRST = new Set(["AI assistant", "quantities"]);
  $effect(() => {
    if (!section || !content) return;
    setOpen("settings." + section, true);
    if (section === "Units") setOpen("settings.quantities", true);
    requestAnimationFrame(() => {
      const h = [...content!.querySelectorAll("h4")].find((x) => x.textContent?.trim() === section);
      h?.scrollIntoView({ block: "start" });
    });
  });
  const opened = (id: string) => isOpen("settings." + id, !FOLDED_FIRST.has(id));

  const QUANTITIES: [Dim, string][] = [
    ["LENGTH", "Length (parts)"], ["DISTANCE", "Distance and altitude"], ["VELOCITY", "Speed"],
    ["ACCELERATION", "Acceleration"], ["MASS", "Mass"], ["FORCE", "Force and thrust"], ["IMPULSE", "Impulse"],
    ["TIME", "Time"], ["ANGLE", "Angle"], ["AREA", "Area"], ["VOLUME", "Volume"], ["PRESSURE", "Pressure"],
    ["DENSITY", "Density"], ["TEMPERATURE", "Temperature"],
  ];
  const THEMES: [Theme, string][] = [["system", "System"], ["light", "Light"], ["dark", "Dark"]];
  const DENSITIES: [Density, string][] = [["compact", "Compact"], ["normal", "Normal"], ["comfortable", "Comfortable"]];
  const MOTIONS: [Motion, string][] = [["system", "System"], ["reduce", "Reduced"], ["full", "Full"]];
  const MARKS: [Mark, string][] = [["point", "1,234.5"], ["comma", "1 234,5"]];
  const BACKUP_MINUTES: [number, string][] = [[0, "Off"], [2, "2 min"], [5, "5 min"], [10, "10 min"], [15, "15 min"]];

  // Backups: how often, and where (the engine keeps them; the interval is one of the stored preferences).
  let backup = $state<{ folder: string; minutes: number; keep: number; backups: unknown[] } | null>(null);
  async function loadBackups() {
    backup = await call<{ folder: string; minutes: number; keep: number; backups: unknown[] }>("app_backups", {}).catch(() => null);
  }
  onMount(loadBackups);
  function setBackupMinutes(m: number) {
    try {
      if (m === 5) localStorage.removeItem("rocketmaxxing.backupMinutes");
      else localStorage.setItem("rocketmaxxing.backupMinutes", String(m));
    } catch {
      /* the engine is told below */
    }
    // The engine reads the stored preferences: send the change now rather than with the next batch.
    call("app_prefs", { set: { "rocketmaxxing.backupMinutes": m === 5 ? null : String(m) } }).then(loadBackups).catch(() => {});
    if (backup) backup.minutes = m;
  }
  const COLOURS = SCHEMES.filter((x) => !x.vision);
  const VISION = SCHEMES.filter((x) => x.vision);
</script>

{#snippet tile(sc: Scheme)}
  <button class="scheme" class:on={prefs.scheme === sc.id} role="radio" aria-checked={prefs.scheme === sc.id} title={sc.vision ?? sc.name}
    onclick={() => setScheme(sc.id)}>
    <span class="sw" style:background={sc.swatch[0]}>
      <i style:background={sc.swatch[1]}></i><i style:background={sc.swatch[2]}></i>
    </span>
    <span class="nm">{sc.name}</span>
  </button>
{/snippet}

<svelte:window onkeydown={(e) => e.key === "Escape" && close()} />

<div class="scrim" role="presentation" onclick={(e) => e.target === e.currentTarget && close()}>
  <div class="dlg" role="dialog" aria-label="Settings">
    <header>
      <h3>Settings</h3>
      <button class="btn ghost" onclick={close} aria-label="Close"><X size={16} /></button>
    </header>

    <div class="content" bind:this={content}>
      <section class="sec">
        <button class="sh" onclick={() => toggle("settings.Units", !FOLDED_FIRST.has("Units"))} aria-expanded={opened("Units")}>
          <span class="si"><Ruler size={15} /></span>
          <h4>Units</h4>
          <span class="chev" class:shut={!opened("Units")}><ChevronDown size={14} /></span>
        </button>
        {#if opened("Units")}
        <div class="sb">
      <div class="row">
        <span>Default units</span>
        <div class="seg">
          <button class:on={units.system === "metric"} onclick={() => setSystem("metric")}>Metric</button>
          <button class:on={units.system === "imperial"} onclick={() => setSystem("imperial")}>Imperial</button>
        </div>
      </div>
      <p class="note">Choosing metric or imperial resets the unit of each quantity.</p>
      <button class="sub" onclick={() => toggle("settings.quantities", false)} aria-expanded={opened("quantities")}>
        <span class="chev" class:shut={!opened("quantities")}><ChevronDown size={14} /></span> Unit of each quantity
      </button>
      {#if opened("quantities")}
      <div class="units">
        {#each QUANTITIES as [dim, label] (dim)}
          <label class="row">
            <span>{label}</span>
            <select class="select" value={unitOf(dim).unit} onchange={(e) => choose(dim, (e.target as HTMLSelectElement).value)}>
              {#each choices(dim) as u (u.unit)}<option value={u.unit}>{pretty(u.unit)}</option>{/each}
            </select>
          </label>
        {/each}
      </div>
      {/if}
      <div class="row">
        <span>Significant figures</span>
        <div class="seg">
          {#each [3, 4, 5] as n (n)}<button class:on={prefs.digits === n} onclick={() => setDigits(n)}>{n}</button>{/each}
        </div>
      </div>
      <div class="row">
        <span>Decimal mark</span>
        <div class="seg">
          {#each MARKS as [m, label] (m)}<button class:on={prefs.mark === m} onclick={() => setMark(m)}>{label}</button>{/each}
        </div>
      </div>
      <p class="note">
        For the numbers the app writes and the boxes you type into. Sentences worked out by the engine (rule check notes,
        analysis results) keep the point.
      </p>
        </div>
        {/if}
      </section>
      <section class="sec">
        <button class="sh" onclick={() => toggle("settings.Appearance", !FOLDED_FIRST.has("Appearance"))} aria-expanded={opened("Appearance")}>
          <span class="si"><Palette size={15} /></span>
          <h4>Appearance</h4>
          <span class="chev" class:shut={!opened("Appearance")}><ChevronDown size={14} /></span>
        </button>
        {#if opened("Appearance")}
        <div class="sb">
      <div class="row">
        <span>Light or dark</span>
        <div class="seg">
          {#each THEMES as [t, label] (t)}<button class:on={prefs.theme === t} onclick={() => setTheme(t)}>{label}</button>{/each}
        </div>
      </div>
      <div class="schemes" role="radiogroup" aria-label="Colour scheme">
        {#each COLOURS as sc (sc.id)}{@render tile(sc)}{/each}
      </div>
      <p class="note">For colour vision: pass, warning and failure colours that stay apart (PASS, WARN and FAIL are always written out too).</p>
      <div class="schemes" role="radiogroup" aria-label="Colour scheme for colour vision">
        {#each VISION as sc (sc.id)}{@render tile(sc)}{/each}
      </div>
      <div class="row">
        <span>Text size</span>
        <div class="seg">
          {#each SIZES as [v, label] (v)}<button class:on={prefs.size === v} onclick={() => setSize(v)} title="{Math.round(v * 100)}%">{label}</button>{/each}
        </div>
      </div>
      <p class="note">Every font, with buttons and boxes growing to fit. Pictures and the 3-D view keep their size.</p>
      <div class="row">
        <span>Density</span>
        <div class="seg">
          {#each DENSITIES as [d, label] (d)}<button class:on={prefs.density === d} onclick={() => setDensity(d)}>{label}</button>{/each}
        </div>
      </div>
      <p class="note">How tightly tables, the parts tree and forms are packed: compact shows more rows at once.</p>
      <div class="row">
        <span>Animations</span>
        <div class="seg">
          {#each MOTIONS as [m, label] (m)}<button class:on={prefs.motion === m} onclick={() => setMotion(m)}>{label}</button>{/each}
        </div>
      </div>
      <p class="note">System follows the computer's own setting. Reduced turns off moving loaders, sliding panels and the 3-D view's eased zoom and explode.</p>
      <div class="row">
        <span>Layout</span>
        <div class="acts">
          <button class="btn" onclick={() => (resetLayout(), toast("Panels, the plot setup and the 3-D view are back as they start"))}
            title="Opens or folds every panel as it starts, and forgets the plot setup and the 3-D view's mode (saved plots stay)">Reset the layout</button>
          <button class="btn" onclick={() => (resetTours(), toast("Each page shows its tour again the next time you open it"))}>Show the tours again</button>
        </div>
      </div>
        </div>
        {/if}
      </section>
      <section class="sec">
        <button class="sh" onclick={() => toggle("settings.Checks", !FOLDED_FIRST.has("Checks"))} aria-expanded={opened("Checks")}>
          <span class="si"><ShieldCheck size={15} /></span>
          <h4>Checks</h4>
          <span class="chev" class:shut={!opened("Checks")}><ChevronDown size={14} /></span>
        </button>
        {#if opened("Checks")}
        <div class="sb">
      <label class="row check">
        <span>Check the rules after each edit</span>
        <input type="checkbox" checked={autoCheck()} onchange={(e) => setAutoCheck((e.target as HTMLInputElement).checked)} />
      </label>
        </div>
        {/if}
      </section>
      <section class="sec">
        <button class="sh" onclick={() => toggle("settings.AI assistant", !FOLDED_FIRST.has("AI assistant"))} aria-expanded={opened("AI assistant")}>
          <span class="si"><Bot size={15} /></span>
          <h4>AI assistant</h4>
          <span class="chev" class:shut={!opened("AI assistant")}><ChevronDown size={14} /></span>
        </button>
        {#if opened("AI assistant")}
        <div class="sb">
      {#if assistant.settings}
        <label class="row check">
          <span>Use an AI assistant beside the design</span>
          <input type="checkbox" checked={assistant.settings.enabled} onchange={(e) => configure({ enabled: (e.target as HTMLInputElement).checked })} />
        </label>
        <AssistantSetup />
        <p class="note">The robot button at the top right opens the assistant.</p>
      {/if}
        </div>
        {/if}
      </section>
      <section class="sec">
        <button class="sh" onclick={() => toggle("settings.Files", !FOLDED_FIRST.has("Files"))} aria-expanded={opened("Files")}>
          <span class="si"><FolderOpen size={15} /></span>
          <h4>Files</h4>
          <span class="chev" class:shut={!opened("Files")}><ChevronDown size={14} /></span>
        </button>
        {#if opened("Files")}
        <div class="sb">
      <div class="row"><span>Designs folder</span><code>{app.session?.workspace}</code></div>
      <div class="row">
        <span>Back up unsaved work</span>
        <div class="seg">
          {#each BACKUP_MINUTES as [m, label] (m)}<button class:on={(backup?.minutes ?? 5) === m} onclick={() => setBackupMinutes(m)}>{label}</button>{/each}
        </div>
      </div>
      <p class="note">
        A copy of each open design with unsaved changes, in the designs folder's backups ({backup?.keep ?? 10} kept per design).
        Your own files are never touched. Recent backups are listed on the Start page.
        {#if backup?.backups.length}<button class="link" onclick={() => run(() => call("app_reveal", { path: backup!.folder }))}>Show the backups folder</button>{/if}
      </p>
      <label class="row check">
        <span>Check for a newer version when RocketMaxxing starts</span>
        <input type="checkbox" checked={checkEnabled()} onchange={(e) => setCheckEnabled((e.target as HTMLInputElement).checked)} />
      </label>
      <p class="note">
        RocketMaxxing {app.session?.version}, engine {app.session?.engineVersion}, OpenRocket {app.session?.openRocketVersion ?? ""}.
        <button class="link" disabled={updates.checking} onclick={checkNow}>{updates.checking ? "Checking…" : "Check now"}</button>
        (asks GitHub's Releases page, at most once a day; nothing about you or your designs is sent)
      </p>
        </div>
        {/if}
      </section>
    </div>
  </div>
</div>

<style>
  .scrim {
    position: fixed;
    inset: 0;
    background: rgb(0 0 0 / 30%);
    display: grid;
    place-items: center;
    z-index: 40;
  }
  .dlg {
    width: min(560px, calc(100vw - 32px));
    max-height: calc(100vh - 40px);
    display: flex;
    flex-direction: column;
    background: var(--surface);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius);
    box-shadow: var(--shadow-pop);
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 8px 8px 8px 16px;
    border-bottom: 1px solid var(--line);
  }
  h3 {
    margin: 0;
    font-size: calc(15px * var(--text));
  }
  .content {
    overflow: auto;
    padding: 0 20px 8px;
  }
  h4 {
    flex: 1;
    margin: 0;
    font-size: calc(13.5px * var(--text));
    text-align: left;
  }
  .sec {
    border-bottom: 1px solid var(--line);
  }
  .sec:last-child {
    border-bottom: 0;
  }
  .sh {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    border: 0;
    background: transparent;
    padding: 14px 0;
    color: var(--ink);
  }
  .si {
    display: grid;
    place-items: center;
    width: 28px;
    height: 28px;
    border-radius: 7px;
    background: var(--select-soft);
    color: var(--select);
  }
  .chev {
    display: flex;
    color: var(--ink-3);
    transition: transform 0.1s;
  }
  .chev.shut {
    transform: rotate(-90deg);
  }
  .sb {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 0 0 16px 38px;
  }
  .sub {
    display: flex;
    align-items: center;
    gap: 6px;
    border: 0;
    background: transparent;
    padding: 4px 0;
    font-weight: 600;
    color: var(--ink-2);
  }
  .row {
    display: grid;
    grid-template-columns: 1fr 200px;
    align-items: center;
    gap: 10px;
    min-height: 32px;
    color: var(--ink-2);
  }
  .row.check input {
    justify-self: start;
    width: 16px;
    height: 16px;
  }
  .units {
    display: flex;
    flex-direction: column;
  }
  .schemes {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(118px, 1fr));
    gap: 8px;
    margin: 8px 0 4px;
  }
  .scheme {
    display: flex;
    flex-direction: column;
    gap: 5px;
    padding: 6px;
    border: 1px solid var(--line);
    border-radius: 6px;
    background: var(--surface);
    text-align: left;
  }
  .scheme:hover {
    border-color: var(--line-strong);
  }
  .scheme.on {
    border-color: var(--select);
    box-shadow: 0 0 0 1px var(--select);
  }
  .sw {
    display: flex;
    align-items: flex-end;
    justify-content: flex-end;
    gap: 4px;
    height: 34px;
    padding: 5px;
    border-radius: 4px;
    border: 1px solid rgb(0 0 0 / 12%);
  }
  .sw i {
    width: 12px;
    height: 12px;
    border-radius: 50%;
    border: 1.5px solid rgb(255 255 255 / 85%);
  }
  .nm {
    font-size: calc(12px * var(--text));
  }
  .seg {
    display: flex;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    overflow: hidden;
  }
  .seg button {
    flex: 1;
    border: 0;
    border-left: 1px solid var(--line);
    background: var(--surface);
    padding: 5px 10px;
    color: var(--ink-2);
    white-space: nowrap;
  }
  .seg button:first-child {
    border-left: 0;
  }
  .seg button:hover:not(.on) {
    background: var(--surface-2);
  }
  /* A row whose choices need more room than a select gets it; the label keeps at least its own line. */
  .row:has(.seg) {
    grid-template-columns: minmax(110px, 1fr) auto;
  }
  .seg button.on {
    background: var(--select);
    color: #fff;
  }
  .note {
    margin: 4px 0;
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
  }
  code {
    font-size: calc(12px * var(--text));
    overflow-wrap: anywhere;
  }
  .link {
    border: 0;
    padding: 0;
    background: none;
    color: var(--select);
    font: inherit;
    cursor: pointer;
    text-decoration: underline;
  }
  .link:disabled {
    color: var(--ink-3);
    cursor: default;
  }
  .acts {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    justify-content: flex-end;
  }
</style>
