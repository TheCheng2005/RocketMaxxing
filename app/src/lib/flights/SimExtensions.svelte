<script lang="ts">
  import { untrack } from "svelte";
  import { Plus, Trash2, Power } from "@lucide/svelte";
  import { call } from "../api";
  import { app, act, ask, toast, type TreeNode } from "../state.svelte";

  // OpenRocket's Extensions tab for one simulation: air-start, roll control, damping, stop, CSV, a script, a Java
  // class. Each change flies the simulation again. A script runs as code on this computer: it stays off until the
  // person here reads it and turns it on.
  let { sim }: { sim: number } = $props();

  interface Setting {
    key: string;
    label: string;
    help: string;
    unit: string;
  }
  interface Kind {
    type: string;
    name: string;
    about: string;
    settings: Setting[];
  }
  interface Ext {
    index: number;
    type: string;
    name: string;
    about?: string;
    settings: Record<string, any>;
    off?: string;
  }
  let kinds = $state<Kind[]>([]);
  let list = $state<Ext[]>([]);
  let adding = $state("AirStart");
  let scripts = $state<Record<number, string>>({});
  const savedScripts: Record<number, string> = {};

  async function load() {
    if (!app.design) return;
    if (!kinds.length) kinds = (await call<{ types: Kind[] }>("simulation_extensions", { action: "types" }).catch(() => ({ types: [] }))).types;
    const r = await call<{ extensions: Ext[] }>("simulation_extensions", { designId: app.design.designId, simulation: String(sim) }).catch(() => null);
    list = r?.extensions ?? [];
    // A script being typed is kept when the page reloads for another edit (the assistant's, an undo): only boxes
    // still showing what was saved take the new text.
    const next: Record<number, string> = {};
    for (const e of list.filter((x) => x.type === "ScriptingExtension")) {
      const now = String(e.settings.script ?? "");
      const typing = scripts[e.index] !== undefined && scripts[e.index] !== savedScripts[e.index];
      next[e.index] = typing ? scripts[e.index] : now;
      savedScripts[e.index] = now;
    }
    scripts = next;
  }
  $effect(() => {
    void sim;
    void app.revision;
    untrack(load);
  });

  /** The design's fin sets by name, for roll control's "which fins turn". */
  let finSets = $derived.by(() => {
    const out: string[] = [];
    const walk = (n?: TreeNode) => {
      if (!n) return;
      if (/FinSet$/.test(n.type) && !out.includes(n.name)) out.push(n.name);
      n.children.forEach(walk);
    };
    walk(app.design?.tree);
    return out;
  });
  const kindOf = (e: Ext) => kinds.find((k) => k.type === e.type);
  /** The settings to show: the known ones in order, then any others the extension has. */
  function rows(e: Ext): Setting[] {
    const k = kindOf(e);
    const known = (k?.settings ?? []).filter((s) => s.key !== "script" && s.key !== "enabled" && s.key !== "language");
    const extra = Object.keys(e.settings)
      .filter((key) => !known.some((s) => s.key.toLowerCase() === key.toLowerCase()) && !["script", "enabled", "language"].includes(key))
      .map((key) => ({ key, label: key, help: "", unit: "" }));
    return [...known, ...extra];
  }
  const value = (e: Ext, key: string) => {
    const k = Object.keys(e.settings).find((x) => x.toLowerCase() === key.toLowerCase());
    return k === undefined ? "" : String(e.settings[k]);
  };

  async function change(args: Record<string, unknown>, done?: string) {
    const r = await act("simulation_extensions", { simulation: String(sim), ...args });
    if (r) {
      if (r.flightFailed) toast(`The simulation stopped: ${r.flightFailed}`, "error");
      else if (done) toast(done + (r.flight?.flight?.apogee ? `; apogee ${r.flight.flight.apogee}` : ""));
      await load();
    }
    return r;
  }
  const add = () => change({ action: "add", type: adding }, `Added ${kinds.find((k) => k.type === adding)?.name ?? adding}`);
  const remove = (e: Ext) => change({ action: "remove", index: e.index }, `Removed ${e.name}`);
  const setOne = (e: Ext, key: string, v: string) => v !== value(e, key) && change({ action: "set", index: e.index, settings: { [key]: v } }, `${e.name}: ${key} set`);
  const saveScript = (e: Ext) => change({ action: "set", index: e.index, settings: { script: scripts[e.index] ?? "" } }, "Script saved; it is off until you turn it on");

  async function turnOn(e: Ext) {
    const ok = await ask(
      "Turn this script on?",
      "It runs as code on this computer during every flight of this simulation. Read it first, and only turn on scripts you or your team wrote. Type yes to turn it on.",
      "",
    );
    if (ok?.trim().toLowerCase() !== "yes") return;
    await change({ action: "set", index: e.index, settings: { enabled: true } }, "Script on: the simulation flew with it");
  }
  const turnOff = (e: Ext) => change({ action: "set", index: e.index, settings: { enabled: false } }, "Script off");
</script>

<div class="ext">
  {#each list as e (e.index)}
    <div class="card">
      <div class="top">
        <b>{e.name}</b>
        {#if e.type === "ScriptingExtension"}<span class="state" class:on={!e.off}>{e.off ? "Off" : "On"}</span>{/if}
        <span class="grow"></span>
        <button class="tool" title="Remove {e.name}" onclick={() => remove(e)}><Trash2 size={13} /></button>
      </div>
      {#if e.about}<p class="about">{e.about}</p>{/if}
      {#if e.type === "ScriptingExtension"}
        <textarea class="input code" spellcheck="false" rows="9" bind:value={scripts[e.index]} placeholder={"// Define any of OpenRocket's listener functions, e.g.\nfunction postStep(status) {\n  // status.getRocketPosition(), status.getRocketVelocity(), status.getSimulationTime() ...\n}"}></textarea>
        <div class="acts">
          <button class="btn small" disabled={(scripts[e.index] ?? "") === String(e.settings.script ?? "")} onclick={() => saveScript(e)}>Save script</button>
          {#if e.off}
            <button class="btn small primary" disabled={!String(e.settings.script ?? "").trim()} onclick={() => turnOn(e)}><Power size={13} /> Turn on…</button>
          {:else}
            <button class="btn small" onclick={() => turnOff(e)}><Power size={13} /> Turn off</button>
          {/if}
        </div>
        {#if e.off}<p class="warn">{e.off}</p>{/if}
      {:else}
        <div class="rows">
          {#each rows(e) as s (s.key)}
            <label class="row" title={s.help}>
              <span>{s.label}{#if s.unit}<small> ({s.unit})</small>{/if}</span>
              {#if s.key === "controlFinName" && finSets.length}
                <select class="select" value={value(e, s.key)} onchange={(ev) => setOne(e, s.key, (ev.target as HTMLSelectElement).value)}>
                  {#if !finSets.includes(value(e, s.key))}<option value={value(e, s.key)}>{value(e, s.key)} (not in the design)</option>{/if}
                  {#each finSets as n (n)}<option value={n}>{n}</option>{/each}
                </select>
              {:else}
              <input class="input" value={value(e, s.key)} onblur={(ev) => setOne(e, s.key, (ev.target as HTMLInputElement).value)} onkeydown={(ev) => ev.key === "Enter" && (ev.target as HTMLInputElement).blur()} />
              {/if}
            </label>
          {/each}
        </div>
      {/if}
    </div>
  {:else}
    <p class="muted">None. Extensions change how this simulation flies: an air-start for an upper stage, active roll control, a script of your own.</p>
  {/each}
  <div class="add">
    <select class="select" bind:value={adding} aria-label="Extension to add">
      {#each kinds as k (k.type)}<option value={k.type} title={k.about}>{k.name}</option>{/each}
    </select>
    <button class="btn small" onclick={add}><Plus size={13} /> Add</button>
  </div>
</div>

<style>
  .ext {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 4px 0 8px;
  }
  .card {
    border: 1px solid var(--line);
    border-radius: var(--radius);
    padding: 8px 10px;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .top {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .grow {
    flex: 1;
  }
  .state {
    font-size: calc(11px * var(--text));
    padding: 1px 7px;
    border-radius: 999px;
    background: var(--surface-2, var(--line));
    color: var(--ink-2);
  }
  .state.on {
    background: var(--good);
    color: #fff;
  }
  .about,
  .warn {
    margin: 0;
    font-size: calc(12px * var(--text));
    color: var(--ink-2);
  }
  .warn {
    color: var(--warn, #b26a00);
  }
  .rows {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 8px;
    align-items: center;
    font-size: calc(12.5px * var(--text));
  }
  .row small {
    color: var(--ink-3);
  }
  .code {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: calc(12px * var(--text));
    width: 100%;
    resize: vertical;
    min-height: 8em;
  }
  .acts,
  .add {
    display: flex;
    gap: 6px;
    align-items: center;
  }
  .btn.small {
    height: calc(26px * var(--text));
    padding: 0 8px;
    font-size: calc(12px * var(--text));
    display: inline-flex;
    align-items: center;
    gap: 5px;
  }
</style>
