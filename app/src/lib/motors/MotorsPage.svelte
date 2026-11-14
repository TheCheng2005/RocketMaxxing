<script lang="ts">
  import { Flame, Layers, Umbrella, Plus, Copy, PencilLine, Trash2, X } from "@lucide/svelte";
  import { untrack } from "svelte";
  import { call } from "../api";
  import { act, app, ask, run, selectConfig } from "../state.svelte";
  import { fmt, num, type Dim } from "../units.svelte";
  import Panel from "../Panel.svelte";
  import MotorPicker from "./MotorPicker.svelte";
  import QuantityInput from "../design/QuantityInput.svelte";
  import { context, pageMenu, type MenuItem } from "../menu/menu.svelte";
  import { analysis } from "../analysis/store.svelte";
  import { ui, take } from "../search/ui.svelte";
  import { toast } from "../state.svelte";

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

  pageMenu(() => [
    { label: "New configuration…", run: newConfig },
    { label: "Copy the active configuration", off: !data && "Loading", run: () => data && act("app_config", { action: "copy", configuration: data.selected }) },
    { label: "Rename the active configuration…", off: !data && "Loading", run: () => rename() },
    { label: "Rank motors for this rocket", run: toAnalysis("rank_motors") },
  ]);
</script>

{#snippet configCell(c: { id: string; name: string }, table: string)}
  <td class="cfg" use:context={() => configItems(c.id)}>
    <label class="radio">
      <input type="radio" name="active-{table}" checked={c.id === data?.selected} onchange={() => selectConfig(c.id)} />
      <span>{c.name}</span>
    </label>
  </td>
{/snippet}

<div class="page">
  <div class="toolbar">
    <button class="btn" onclick={newConfig}><Plus size={15} /> New configuration</button>
    <button class="btn" disabled={!data} onclick={() => data && act("app_config", { action: "copy", configuration: data.selected })}><Copy size={15} /> Copy</button>
    <button class="btn" disabled={!data} onclick={() => rename()}><PencilLine size={15} /> Rename</button>
    <button class="btn" disabled={(data?.configurations.length ?? 0) < 2} onclick={() => data && act("app_config", { action: "remove", configuration: data.selected })}><Trash2 size={15} /> Delete</button>
    <span class="muted note">Copy, rename and delete act on the selected configuration.</span>
  </div>

  {#if data}
    <Panel id="motors.motors" icon={Flame} title="Motors">
      <table class="grid">
        <thead>
          <tr>
            <th class="cfg">Configuration</th>
            {#each data.mounts as m (m.id)}<th>{m.name} <span class="sub">({m.stage}, Ø {fmt(m.innerDiameter, "LENGTH")})</span></th>{/each}
          </tr>
        </thead>
        <tbody>
          {#each data.configurations as c (c.id)}
            <tr class:sel={c.id === data.selected}>
              {@render configCell(c, "motors")}
              {#each data.mounts as m (m.id)}
                {@const mo = m.motors[c.id]}
                <td class="cell" use:context={() => motorItems(m, c.id)}>
                  {#if mo?.designation}
                    <div class="motor">
                      <button class="pick" onclick={() => (picking = { mount: m, configuration: c.id })} title="Change motor">
                        <b>{mo.manufacturer} {mo.designation}</b>
                        <span class="muted">{fmt(mo.totalImpulse, "IMPULSE")}, {fmt(mo.burnTime, "TIME", 3)}</span>
                      </button>
                      <select class="select delay" title="Ejection delay" value={mo.plugged ? "plugged" : String(mo.ejectionDelay)} onchange={(e) => setDelay(m, c.id, (e.target as HTMLSelectElement).value)}>
                        {#each delays(mo) as d}<option value={String(d)}>{num(d, 3)} s</option>{/each}
                        <option value="plugged">Plugged</option>
                      </select>
                      <button class="x" title="Remove motor" onclick={() => act("app_motor_delay", { mount: m.id, configuration: c.id, remove: true })}><X size={14} /></button>
                    </div>
                  {:else}
                    <button class="empty" onclick={() => (picking = { mount: m, configuration: c.id })}>None (click to choose)</button>
                  {/if}
                </td>
              {/each}
              {#if data.mounts.length === 0}<td class="muted">No motor mount. Tick "This is a motor mount" on an inner tube or body tube.</td>{/if}
            </tr>
          {/each}
        </tbody>
        {#if data.mounts.length}
          <tfoot>
            <tr>
              <th class="cfg" title="Where the motor sits along its mount. It is the same in every configuration, as in OpenRocket.">Position in the mount</th>
              {#each data.mounts as m (m.id)}
                {@const mo = m.motors[data.selected]}
                <td class="cell pos">
                  <label class="over" for="overhang-{m.id}">Overhang
                    <QuantityInput id="overhang-{m.id}" value={m.overhang} dim="LENGTH" commit={(v) => act("app_edit", { component: m.id, key: "motorOverhang", value: v })} />
                  </label>
                  <div class="fit" class:warn={mo?.length && m.length + m.overhang - mo.length < -1e-6}>{sits(m, mo)}</div>
                </td>
              {/each}
            </tr>
          </tfoot>
        {/if}
      </table>
    </Panel>

    <Panel id="motors.recovery" icon={Umbrella} title="Recovery">
      <table class="grid">
        <thead>
          <tr>
            <th class="cfg">Configuration</th>
            {#each data.recovery as r (r.id)}<th>{r.name} <span class="sub">({r.type === "Parachute" ? "parachute" : "streamer"})</span></th>{/each}
          </tr>
        </thead>
        <tbody>
          {#each data.configurations as c (c.id)}
            <tr class:sel={c.id === data.selected}>
              {@render configCell(c, "recovery")}
              {#each data.recovery as r (r.id)}
                {@const d = r.deployment[c.id]}
                <td class="cell" use:context={() => deployItems(r, c.id)}>
                  <button class="pick" onclick={() => (editing = { kind: "deploy", id: r.id, name: r.name, configuration: c.id, event: d.event, altitude: d.altitude, delay: d.delay, all: false })}>
                    {deployText(d)}
                  </button>
                </td>
              {/each}
              {#if data.recovery.length === 0}<td class="muted">No parachute or streamer.</td>{/if}
            </tr>
          {/each}
        </tbody>
      </table>
    </Panel>

    <Panel id="motors.stages" icon={Layers} title="Stages" initial={stages.length > 0}>
      {#if stages.length === 0}
        <p class="muted pad">Single stage: nothing separates.</p>
      {:else}
        <table class="grid">
          <thead>
            <tr><th class="cfg">Configuration</th>{#each stages as s (s.id)}<th>{s.name} <span class="sub">(separation)</span></th>{/each}</tr>
          </thead>
          <tbody>
            {#each data.configurations as c (c.id)}
              <tr class:sel={c.id === data.selected}>
                {@render configCell(c, "stages")}
                {#each stages as s (s.id)}
                  {@const sp = s.separation[c.id]}
                  <td class="cell" use:context={() => stageItems(s, c.id)}>
                    <button class="pick" onclick={() => (editing = { kind: "sep", id: s.id, name: s.name, configuration: c.id, event: sp.event, altitude: 0, delay: sp.delay, all: false })}>
                      {SEP[sp.event] ?? sp.event}{sp.delay > 0 ? " + " + num(sp.delay, 3) + " s" : ""}
                    </button>
                  </td>
                {/each}
              </tr>
            {/each}
          </tbody>
        </table>
      {/if}
    </Panel>
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
    font-size: 12px;
  }
  .sub {
    font-weight: 400;
    color: var(--ink-3);
  }
  .cfg {
    width: 240px;
    min-width: 180px;
  }
  .radio {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  .radio input {
    accent-color: var(--select);
  }
  .cell {
    min-width: 230px;
  }
  .motor {
    display: flex;
    align-items: center;
    gap: 4px;
  }
  .pick {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    border: 1px solid transparent;
    background: transparent;
    border-radius: var(--radius);
    padding: 2px 6px;
    text-align: left;
    min-width: 0;
  }
  .pick:hover {
    border-color: var(--line-strong);
    background: var(--surface);
  }
  .pick b {
    font-weight: 600;
  }
  .pick .muted {
    font-size: 12px;
  }
  .delay {
    width: 92px;
  }
  .x {
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    border: 0;
    background: transparent;
    color: var(--ink-3);
    border-radius: 3px;
  }
  .x:hover {
    background: var(--surface-3);
    color: var(--bad);
  }
  .empty {
    border: 1px dashed var(--line-strong);
    background: transparent;
    border-radius: var(--radius);
    padding: 4px 8px;
    color: var(--ink-3);
    width: 100%;
    text-align: left;
  }
  .empty:hover {
    border-color: var(--select);
    color: var(--ink);
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
    font-size: 14px;
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
  tfoot th,
  tfoot td {
    border-top: 1px solid var(--line-strong);
    vertical-align: top;
    text-align: left;
  }
  tfoot th {
    font-weight: 600;
    padding-top: 10px;
  }
  .over {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    font-size: 12.5px;
    color: var(--ink-2);
  }
  .over :global(input) {
    width: 84px;
  }
  .fit {
    margin-top: 4px;
    font-size: 12px;
    color: var(--ink-3);
    max-width: 46ch;
  }
  .fit.warn {
    color: var(--warn);
  }
</style>
