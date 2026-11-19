<script lang="ts">
  import { Flame, Layers, Umbrella, Plus, Copy, PencilLine, Trash2, X, FileUp, TriangleAlert, Eraser } from "@lucide/svelte";
  import { untrack } from "svelte";
  import { call } from "../api";
  import { act, app, ask, confirmIt, run, selectConfig } from "../state.svelte";
  import { fmt, num, type Dim } from "../units.svelte";
  import Panel from "../Panel.svelte";
  import MotorPicker from "./MotorPicker.svelte";
  import QuantityInput from "../design/QuantityInput.svelte";
  import { context, pageMenu, type MenuItem } from "../menu/menu.svelte";
  import { analysis } from "../analysis/store.svelte";
  import { ui, take } from "../search/ui.svelte";
  import { toast } from "../state.svelte";
  import { keys } from "../keys";

  // OpenRocket's "Motors & Configuration" tab: a row per flight configuration; motors per mount, recovery
  // deployment per device and separation per stage.

  interface Mount {
    id: string;
    name: string;
    stage: string;
    innerDiameter: number;
    /** The mount tube's length, and how far the motor sticks out of its aft end (negative: recessed). */
    length: number;
    overhang: number;
    motors: Record<string, any>;
  }
  interface Recovery {
    id: string;
    name: string;
    type: string;
    deployment: Record<string, { event: string; altitude: number; delay: number }>;
  }
  interface Stage {
    id: string;
    name: string;
    stageNumber: number;
    separation: Record<string, { event: string; delay: number }>;
  }
  interface Configs {
    configurations: { id: string; name: string; hasMotors: boolean }[];
    selected: string;
    mounts: Mount[];
    recovery: Recovery[];
    stages: Stage[];
    missing?: { configuration: string; mount: string; manufacturer: string; designation: string }[];
  }

  let data = $state<Configs | null>(null);
  let picking = $state<{ mount: Mount; configuration: string } | null>(null);
  let editing = $state<null | { kind: "deploy" | "sep"; id: string; name: string; configuration: string; event: string; altitude: number; delay: number; all: boolean }>(null);

  async function load() {
    if (!app.design) return;
    data = (await run(() => call<Configs>("app_configs", { designId: app.design!.designId }))) ?? null;
  }

  $effect(() => {
    void app.revision;
    untrack(load); // load() touches other state (the busy counter): only a new revision reloads
  });

  const DEPLOY: Record<string, string> = { APOGEE: "Apogee", ALTITUDE: "Altitude", EJECTION: "Motor ejection charge",
    LOWER_STAGE_SEPARATION: "Separation of the stage below", LAUNCH: "Launch", NEVER: "Never" };
  const SEP: Record<string, string> = { UPPER_IGNITION: "Ignition of the stage above", IGNITION: "Ignition of this stage",
    BURNOUT: "Burnout of this stage", EJECTION: "Ejection charge of this stage", APOGEE: "Apogee", LAUNCH: "Launch",
    NEVER: "Never" };

  function deployText(d: { event: string; altitude: number; delay: number }) {
    let t = d.event === "ALTITUDE" ? "At " + fmt(d.altitude, "DISTANCE") : DEPLOY[d.event] ?? d.event;
    if (d.delay > 0) t += " + " + num(d.delay, 3) + " s";
    return t;
  }

  async function newConfig() {
    const name = await ask("New flight configuration", "Name (empty: named after its motors)", "");
    await act("flight_configuration", name ? { action: "create", name } : { action: "create" });
  }

  async function rename(id = data?.selected) {
    const c = data?.configurations.find((x) => x.id === id);
    if (!c) return;
    const name = await ask("Rename configuration", "Name", c.name);
    if (name) await act("flight_configuration", { action: "rename", configuration: c.id, name });
  }

  async function setDelay(mount: Mount, configuration: string, v: string) {
    await act("app_motor_delay", v === "plugged" ? { mount: mount.id, configuration, plugged: true } : { mount: mount.id, configuration, ejectionDelay: Number(v) });
  }

  /** The motor's own delays (from its data file), plus the one set now. */
  function delays(mo: any): number[] {
    const list: number[] = (mo.delays ?? []).filter((d: number | null) => d !== null && Number.isFinite(d));
    if (!mo.plugged && !list.some((d) => Math.abs(d - mo.ejectionDelay) < 1e-6)) list.push(mo.ejectionDelay);
    return list.sort((a, b) => a - b);
  }

  async function saveEdit() {
    const e = editing;
    if (!e) return;
    const configuration = e.all ? "all" : e.configuration;
    if (e.kind === "deploy") {
      await act("set_deployment", { component: e.id, event: e.event.toLowerCase(), altitude: e.altitude, delay: e.delay, configuration });
    } else {
      await act("set_stage_separation", { stage: e.id, event: e.event.toLowerCase(), delay: e.delay, configuration });
    }
    editing = null;
  }

  let stages = $derived((data?.stages ?? []).filter((s) => s.stageNumber > 0));

  // ------------------------------------------------------------------ right-click menus
  const toAnalysis = (tool: string) => () => {
    analysis.selected = tool;
    app.page = "analysis";
  };
  function configItems(id: string): MenuItem[] {
    const c = data?.configurations.find((x) => x.id === id);
    if (!c || !data) return [];
    return [
      { header: c.name },
      { label: "Make active", checked: id === data.selected, run: () => selectConfig(id) },
      { label: "Rename…", run: () => rename(id) },
      { label: "Copy", run: () => act("app_config", { action: "copy", configuration: id }) },
      { label: "Delete", danger: true, off: data.configurations.length < 2 && "The only configuration", run: () => act("app_config", { action: "remove", configuration: id }) },
      "-",
      { label: "New configuration…", run: newConfig },
    ];
  }
  /** In words, where the motor of the selected configuration ends up in its mount with this overhang. */
  function sits(m: Mount, mo: any): string {
    const out = m.overhang > 1e-6 ? `sticks out ${fmt(m.overhang, "LENGTH")} behind the mount`
      : m.overhang < -1e-6 ? `sits ${fmt(-m.overhang, "LENGTH")} inside the mount's aft end` : "is flush with the mount's aft end";
    if (!mo?.length) return `A motor ${out}. Mount ${fmt(m.length, "LENGTH")} long.`;
    const spare = m.length + m.overhang - mo.length;
    const front = spare < -1e-6 ? `and reaches ${fmt(-spare, "LENGTH")} past the mount's forward end: check what is ahead of it`
      : spare > 1e-6 ? `with ${fmt(spare, "LENGTH")} of the mount free ahead of it` : "and fills the mount";
    return `${mo.designation} (${fmt(mo.length, "LENGTH")}) ${out}, ${front}. Mount ${fmt(m.length, "LENGTH")} long.`;
  }

  function motorItems(m: Mount, cid: string): MenuItem[] {
    const mo = m.motors[cid];
    const c = data?.configurations.find((x) => x.id === cid);
    const has = !!mo?.designation;
    const everywhere = async () => {
      for (const x of data?.configurations ?? []) {
        if (x.id === cid) continue;
        await act("set_motor", { mount: m.id, motor: mo.digest, configuration: x.id, ...(mo.plugged ? {} : { ejectionDelay: mo.ejectionDelay }) });
      }
    };
    return [
      { header: `${m.name}, ${c?.name ?? ""}` },
      { label: has ? "Change motor…" : "Choose motor…", run: () => (picking = { mount: m, configuration: cid }) },
      has ? { label: "Remove motor", danger: true, run: () => act("app_motor_delay", { mount: m.id, configuration: cid, remove: true }) } : null,
      ...(has ? [{ header: "Ejection delay" } as MenuItem, ...delays(mo).map((d) => ({ label: `${num(d, 3)} s`, checked: !mo.plugged && Math.abs(d - mo.ejectionDelay) < 1e-6, run: () => setDelay(m, cid, String(d)) })), { label: "Plugged (no charge)", checked: !!mo.plugged, run: () => setDelay(m, cid, "plugged") }] : []),
      "-",
      has ? { label: "Same motor in every configuration", off: (data?.configurations.length ?? 0) < 2 && "Only one configuration", run: everywhere } : null,
      { label: "Rank motors for this rocket", run: toAnalysis("rank_motors") },
      { label: "Best ejection delay (Flights)", off: !has && "No motor", run: () => (app.page = "flights") },
    ].filter(Boolean) as MenuItem[];
  }
  function deployItems(r: Recovery, cid: string): MenuItem[] {
    const d = r.deployment[cid];
    const edit = (all: boolean) => () => (editing = { kind: "deploy", id: r.id, name: r.name, configuration: cid, event: d.event, altitude: d.altitude, delay: d.delay, all });
    const quick = (event: string) => () => act("set_deployment", { component: r.id, event: event.toLowerCase(), altitude: d.altitude, delay: 0, configuration: cid });
    return [
      { header: r.name },
      { label: "At apogee", checked: d.event === "APOGEE" && d.delay === 0, run: quick("APOGEE") },
      { label: `At ${fmt(d.altitude, "DISTANCE")}`, checked: d.event === "ALTITUDE", run: quick("ALTITUDE") },
      { label: "Edit deployment…", run: edit(false) },
      { label: "Edit for every configuration…", run: edit(true) },
      "-",
      { label: "Size this parachute", run: toAnalysis("size_parachute") },
      { label: "Recovery loads and shear pins", run: toAnalysis("recovery_analysis") },
      { label: "Late deployment", run: toAnalysis("deployment_delay_sweep") },
    ];
  }
  function stageItems(st: Stage, cid: string): MenuItem[] {
    const sp = st.separation[cid];
    const edit = (all: boolean) => () => (editing = { kind: "sep", id: st.id, name: st.name, configuration: cid, event: sp.event, altitude: 0, delay: sp.delay, all });
    return [{ header: st.name }, { label: "Edit separation…", run: edit(false) }, { label: "Edit for every configuration…", run: edit(true) }];
  }

  // Asked for from the search: the motor picker, straight at importing a file or making your own motor.
  let pickerStart = $state<"import" | "custom" | undefined>();
  $effect(() => {
    void ui.request;
    if (!data) return;
    const r = take("motor");
    if (!r) return;
    const m = data.mounts[0];
    if (!m) return void toast("No motor mount: tick \"This is a motor mount\" on a tube first.", "error");
    pickerStart = r.detail === "import" || r.detail === "custom" ? r.detail : undefined;
    picking = { mount: m, configuration: data.selected };
  });

  /** Start fresh: one empty configuration (the active one, emptied), simulations kept and pointed at it. */
  async function clearConfigs() {
    if (!data) return;
    const n = data.configurations.length;
    const sure = await confirmIt(
      "Clear all configurations",
      `${n > 1 ? `The other ${n - 1} configuration${n === 2 ? "" : "s"} are deleted and this one is emptied` : "This configuration is emptied"}: no motors, and parachute deployment and stage separation back to each part's own setting. The simulations stay, with their launch conditions, and fly this configuration once it has motors. Undo (${keys("Z")}) brings the configurations back.`,
      "Clear configurations",
    );
    if (sure) await act("app_clear", { what: "configurations" });
  }

  pageMenu(() => [
    { label: "New configuration…", run: newConfig },
    { label: "Clear all configurations…", danger: true, off: !data && "Loading", run: clearConfigs },
    { label: "Copy the active configuration", off: !data && "Loading", run: () => data && act("app_config", { action: "copy", configuration: data.selected }) },
    { label: "Rename the active configuration…", off: !data && "Loading", run: () => rename() },
    { label: "Rank motors for this rocket", run: toAnalysis("rank_motors") },
  ]);
</script>

<div class="page">
  <div class="toolbar">
    <!-- One configuration at a time: choose it here (it becomes the active one everywhere), then everything below is it. -->
    <label class="cfgpick" use:context={() => (data ? configItems(data.selected) : [])}>
      <span>Configuration</span>
      <select class="select" value={data?.selected ?? ""} disabled={!data} onchange={(e) => selectConfig((e.target as HTMLSelectElement).value)} aria-label="Flight configuration">
        {#each data?.configurations ?? [] as c (c.id)}<option value={c.id}>{c.name}{data?.missing?.some((x) => x.configuration === c.id) ? " (motor missing)" : c.hasMotors || /no motors/i.test(c.name) ? "" : " (no motor)"}</option>{/each}
      </select>
    </label>
    {#if data}<span class="muted note">{data.configurations.length} configuration{data.configurations.length === 1 ? "" : "s"}</span>{/if}
    <span class="grow"></span>
    <button class="btn" onclick={newConfig}><Plus size={15} /> New</button>
    <button class="btn" disabled={!data} onclick={() => data && act("app_config", { action: "copy", configuration: data.selected })} title="A copy of this configuration, to change one thing"><Copy size={15} /> Copy</button>
    <button class="btn" disabled={!data} onclick={() => rename()}><PencilLine size={15} /> Rename</button>
    <button class="btn" disabled={(data?.configurations.length ?? 0) < 2} onclick={() => data && act("app_config", { action: "remove", configuration: data.selected })}><Trash2 size={15} /> Delete</button>
    <button class="btn" disabled={!data} onclick={clearConfigs} title="Start fresh: one empty configuration"><Eraser size={15} /> Clear all…</button>
  </div>

  {#if data}
    {@const cid = data.selected}
    <Panel id="motors.motors" icon={Flame} title="Motors">
      {#if data.mounts.length === 0}
        <p class="muted pad">No motor mount. Tick "This is a motor mount" on an inner tube or body tube (Design page).</p>
      {:else}
        <div class="cards">
          {#each data.mounts as m (m.id)}
            {@const mo = m.motors[cid]}
            <div class="card" use:context={() => motorItems(m, cid)}>
              <div class="ch"><b>{m.name}</b><span class="sub">{m.stage} · mount Ø {fmt(m.innerDiameter, "LENGTH")}</span></div>
              {#if mo?.designation}
                <div class="mname">{mo.manufacturer} <b>{mo.designation}</b></div>
                <dl class="stats">
                  <dt>Total impulse</dt><dd>{fmt(mo.totalImpulse, "IMPULSE")}</dd>
                  <dt>Average thrust</dt><dd>{fmt(mo.averageThrust, "FORCE")}</dd>
                  <dt>Burn time</dt><dd>{fmt(mo.burnTime, "TIME", 3)}</dd>
                  <dt>Size</dt><dd>Ø {fmt(mo.diameter, "LENGTH")} × {fmt(mo.length, "LENGTH")}</dd>
                  <dt>Ejection delay</dt>
                  <dd>
                    <select class="select delay" title="Ejection delay" value={mo.plugged ? "plugged" : String(mo.ejectionDelay)} onchange={(e) => setDelay(m, cid, (e.target as HTMLSelectElement).value)}>
                      {#each delays(mo) as d}<option value={String(d)}>{num(d, 3)} s</option>{/each}
                      <option value="plugged">Plugged</option>
                    </select>
                  </dd>
                </dl>
                <div class="acts">
                  <button class="btn small" onclick={() => (picking = { mount: m, configuration: cid })}>Change motor…</button>
                  <button class="btn small" onclick={() => { pickerStart = "import"; picking = { mount: m, configuration: cid }; }}><FileUp size={13} /> Import motor file…</button>
                  <button class="btn small ghost" title="Remove the motor from this mount" onclick={() => act("app_motor_delay", { mount: m.id, configuration: cid, remove: true })}><X size={13} /> Remove</button>
                </div>
              {:else}
                {@const lost = data.missing?.find((x) => x.configuration === cid && x.mount === m.id)}
                {#if lost}
                  <div class="lost">
                    <TriangleAlert size={15} />
                    <span>The design file has <b>{lost.manufacturer} {lost.designation}</b> here, but it is not in the motor database (a motor
                      imported into OpenRocket on another computer). Import its .rse or .eng file and it goes back in this mount, in every configuration that uses it.</span>
                  </div>
                {:else}
                  <p class="none">No motor in this mount for this configuration.</p>
                {/if}
                <div class="acts">
                  {#if lost}
                    <button class="btn small primary" onclick={() => { pickerStart = "import"; picking = { mount: m, configuration: cid }; }}><FileUp size={13} /> Import {lost.designation} (.rse, .eng)…</button>
                    <button class="btn small" onclick={() => (picking = { mount: m, configuration: cid })}>Choose another motor…</button>
                  {:else}
                    <button class="btn small primary" onclick={() => (picking = { mount: m, configuration: cid })}>Choose motor…</button>
                    <button class="btn small" onclick={() => { pickerStart = "import"; picking = { mount: m, configuration: cid }; }}><FileUp size={13} /> Import motor file (.rse, .eng)…</button>
                  {/if}
                </div>
              {/if}
              <div class="pos">
                <label class="over" for="overhang-{m.id}" title="Where the motor sits along its mount. It is the same in every configuration, as in OpenRocket.">Overhang
                  <QuantityInput id="overhang-{m.id}" value={m.overhang} dim="LENGTH" commit={(v) => act("app_edit", { component: m.id, key: "motorOverhang", value: v })} />
                </label>
                <div class="fit" class:warn={mo?.length && m.length + m.overhang - mo.length < -1e-6}>{sits(m, mo)}</div>
              </div>
            </div>
          {/each}
        </div>
      {/if}
    </Panel>

    <Panel id="motors.recovery" icon={Umbrella} title="Recovery">
      {#if data.recovery.length === 0}
        <p class="muted pad">No parachute or streamer.</p>
      {:else}
        <div class="rows">
          {#each data.recovery as r (r.id)}
            {@const d = r.deployment[cid]}
            <div class="line" use:context={() => deployItems(r, cid)}>
              <span class="ln">{r.name} <span class="sub">{r.type === "Parachute" ? "parachute" : "streamer"}</span></span>
              <button class="btn small val" onclick={() => (editing = { kind: "deploy", id: r.id, name: r.name, configuration: cid, event: d.event, altitude: d.altitude, delay: d.delay, all: false })} title="Change when it deploys">{deployText(d)}</button>
            </div>
          {/each}
        </div>
      {/if}
    </Panel>

    {#if stages.length}
      <Panel id="motors.stages" icon={Layers} title="Stage separation">
        <div class="rows">
          {#each stages as st (st.id)}
            {@const sp = st.separation[cid]}
            <div class="line" use:context={() => stageItems(st, cid)}>
              <span class="ln">{st.name}</span>
              <button class="btn small val" onclick={() => (editing = { kind: "sep", id: st.id, name: st.name, configuration: cid, event: sp.event, altitude: 0, delay: sp.delay, all: false })} title="Change when it separates">{SEP[sp.event] ?? sp.event}{sp.delay > 0 ? " + " + num(sp.delay, 3) + " s" : ""}</button>
            </div>
          {/each}
        </div>
      </Panel>
    {/if}

    <!-- Every configuration side by side, for a look across them; closed until wanted. -->
    <details class="all">
      <summary>All {data.configurations.length} configurations at a glance</summary>
      <div class="tablewrap">
        <table class="grid">
          <thead><tr><th>Configuration</th>{#each data.mounts as m (m.id)}<th>{m.name}</th>{/each}</tr></thead>
          <tbody>
            {#each data.configurations as c (c.id)}
              <tr class:sel={c.id === cid} onclick={() => selectConfig(c.id)} use:context={() => configItems(c.id)} title="Choose this configuration">
                <td>{c.name}</td>
                {#each data.mounts as m (m.id)}{@const mo = m.motors[c.id]}<td class:muted={!mo?.designation}>{mo?.designation ? `${mo.manufacturer} ${mo.designation}` : "none"}</td>{/each}
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    </details>
  {/if}
</div>

{#if picking}
  <MotorPicker mount={picking.mount} configuration={picking.configuration} start={pickerStart} close={() => ((picking = null), (pickerStart = undefined))} />
{/if}

{#if editing}
  {@const e = editing}
  <div class="scrim" role="presentation" onclick={(ev) => ev.target === ev.currentTarget && (editing = null)}>
    <div class="dialog">
      <h3>{e.kind === "deploy" ? "Deployment" : "Separation"}: {e.name}</h3>
      <label>Event
        <select class="select" bind:value={e.event}>
          {#each Object.entries(e.kind === "deploy" ? DEPLOY : SEP) as [k, v]}<option value={k}>{v}</option>{/each}
        </select>
      </label>
      {#if e.kind === "deploy" && e.event === "ALTITUDE"}
        <label>Altitude (above the pad) <QuantityInput value={e.altitude} dim={"DISTANCE" as Dim} commit={(v) => typeof v === "number" && (e.altitude = v)} /></label>
      {/if}
      <label>Delay <QuantityInput value={e.delay} dim={"TIME" as Dim} commit={(v) => typeof v === "number" && (e.delay = v)} /></label>
      <label class="check"><input type="checkbox" bind:checked={e.all} /> All configurations</label>
      <div class="row">
        <button class="btn" onclick={() => (editing = null)}>Cancel</button>
        <button class="btn primary" onclick={saveEdit}>OK</button>
      </div>
    </div>
  </div>
{/if}

<style>
  .cfgpick {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;
  }
  .cfgpick .select {
    min-width: 260px;
    max-width: 420px;
    font-weight: 400;
  }
  .grow {
    flex: 1;
  }
  .cards {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(calc(320px * var(--text)), 1fr));
    gap: 10px;
    padding: 12px;
  }
  .card {
    border: 1px solid var(--line);
    border-radius: 8px;
    background: var(--surface-2);
    padding: 10px 14px 12px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .ch {
    display: flex;
    flex-direction: column;
  }
  .ch .sub {
    font-size: calc(12px * var(--text));
  }
  .mname {
    font-size: calc(16px * var(--text));
  }
  .stats {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 3px 14px;
    margin: 0;
    align-items: center;
  }
  .stats dt {
    color: var(--ink-3);
    font-size: calc(12.5px * var(--text));
  }
  .stats dd {
    margin: 0;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }
  .lost {
    display: flex;
    gap: 8px;
    align-items: flex-start;
    margin: 0;
    padding: 8px 10px;
    border-radius: 8px;
    background: color-mix(in srgb, var(--warn, #c98a00) 12%, transparent);
    color: var(--ink);
    font-size: calc(13px * var(--text));
    line-height: 1.4;
  }
  .lost :global(svg) {
    flex: none;
    margin-top: 2px;
    color: var(--warn, #c98a00);
  }
  .none {
    margin: 0;
    color: var(--ink-3);
  }
  .acts {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .btn.small {
    height: calc(28px * var(--text));
    padding: 0 10px;
    font-size: calc(12.5px * var(--text));
    display: inline-flex;
    align-items: center;
    gap: 5px;
  }
  .pos {
    border-top: 1px solid var(--line);
    padding-top: 8px;
  }
  .rows {
    display: flex;
    flex-direction: column;
    padding: 6px 12px 10px;
  }
  .line {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 5px 0;
    border-bottom: 1px solid var(--line);
  }
  .line:last-child {
    border-bottom: 0;
  }
  .ln .sub {
    margin-left: 6px;
    font-size: calc(12px * var(--text));
  }
  .all > summary {
    cursor: pointer;
    color: var(--ink-2);
    padding: 4px 2px;
  }
  .tablewrap {
    overflow-x: auto;
    margin-top: 6px;
  }
  .all tr {
    cursor: pointer;
  }
  .page {
    flex: 1;
    min-height: 0;
    overflow: auto;
    display: flex;
    flex-direction: column;
    gap: var(--gap);
    padding: var(--gap);
  }
  .toolbar {
    display: flex;
    align-items: center;
    gap: 4px;
    flex-wrap: wrap;
  }
  .note {
    margin-left: 8px;
    font-size: calc(12px * var(--text));
  }
  .sub {
    font-weight: 400;
    color: var(--ink-3);
  }
  .delay {
    width: 92px;
  }
  .pad {
    padding: 8px 10px;
    margin: 0;
  }
  .scrim {
    position: fixed;
    inset: 0;
    background: rgb(0 0 0 / 30%);
    display: grid;
    place-items: center;
    z-index: 30;
  }
  .dialog {
    width: min(400px, calc(100vw - 32px));
    padding: 14px 16px;
    display: flex;
    flex-direction: column;
    gap: 10px;
    background: var(--surface);
    border: 1px solid var(--line-strong);
    border-radius: var(--radius);
    box-shadow: var(--shadow-pop);
  }
  .dialog h3 {
    margin: 0;
    font-size: calc(14px * var(--text));
  }
  .dialog label {
    display: flex;
    flex-direction: column;
    gap: 4px;
    color: var(--ink-2);
  }
  .dialog label.check {
    flex-direction: row;
    align-items: center;
  }
  .row {
    display: flex;
    justify-content: flex-end;
    gap: 6px;
  }
  .over {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    font-size: calc(12.5px * var(--text));
    color: var(--ink-2);
  }
  .over :global(input) {
    width: 84px;
  }
  .fit {
    margin-top: 4px;
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
    max-width: 46ch;
  }
  .fit.warn {
    color: var(--warn);
  }
</style>
