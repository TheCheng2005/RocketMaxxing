// What the app shows, and everything it does to a design. Each action is one engine tool call followed by a refresh,
// so the screen always shows the engine's own state (and undo covers every edit).

import { plain } from "./issues/explain";
import { call, callFull, ToolError, type Issue } from "./api";
import { record, type Logged } from "./issues/issues.svelte";
import { complete, knownMeshes } from "./design/meshStore";
import { units, setSystem as setUnitSystem } from "./units.svelte";
import { MAC } from "./keys";

export interface TreeNode {
  id: string;
  name: string;
  type: string;
  label: string;
  motorMount?: boolean;
  overridden?: boolean;
  accepts: string[];
  children: TreeNode[];
}

export interface Config {
  id: string;
  name: string;
  hasMotors: boolean;
}

export interface Stability {
  length?: number;
  diameter?: number;
  cg?: number;
  cgBurnout?: number;
  cp?: number | null;
  margin?: number | null;
  marginBurnout?: number | null;
  mass?: number;
  massBurnout?: number;
  floor?: number;
  warnings?: string[];
  error?: string;
}

export interface Design {
  designId: string;
  name: string;
  path: string | null;
  saved: boolean;
  canUndo: number;
  canRedo: number;
  tree: TreeNode;
  configurations: Config[];
  selected: string;
  stability: Stability;
  loadWarnings?: string[];
  /** The imported RASAero (or other) aero table for the whole vehicle: its source, or null. */
  aeroTable?: string | null;
}

export interface FieldDef {
  key: string;
  label: string;
  kind: "quantity" | "number" | "count" | "bool" | "enum" | "material" | "text" | "longtext";
  dim?: string;
  value: any;
  auto?: string;
  isAuto?: boolean;
  options?: { value: string; label: string }[];
  materialType?: string;
  help?: string;
  enabled?: boolean;
}

export interface Editor {
  id: string;
  name: string;
  type: string;
  label: string;
  paint?: string;
  shine?: number;
  presetType?: string;
  preset?: string;
  sections: { title: string; fields: FieldDef[] }[];
  mass?: number;
  componentMass?: number;
  warning?: string;
}

export interface Part {
  i: number;
  name: string;
  kind: string;
  color: string;
  internal: boolean;
  piece: number;
  stage: string;
  stageNo: number;
  x0: number;
  x1: number;
  depth: number;
  cutOpen: boolean;
  plateDirs?: number[][];
  explode: number[];
  id?: string;
  /** Painted parts: 0 (matte) to 1 (glossy). */
  shine?: number;
  type: string;
  /** Left out when the window already has it (see design/meshes.ts). */
  mesh?: string;
  meshId?: string;
}

export interface Model {
  parts: Part[];
  finOut?: number;
  stability: Stability;
}

export interface QuickFlight {
  noMotors?: boolean;
  apogee?: number;
  maxVelocity?: number;
  maxMach?: number;
  maxAcceleration?: number;
  timeToApogee?: number;
  flightTime?: number;
  groundHitVelocity?: number;
  railExitVelocity?: number;
  optimumDelay?: number;
  minStability?: number;
  maxStability?: number;
  problems?: string[];
  error?: string;
}

export interface Session {
  version: string;
  engineVersion: string;
  openRocketVersion?: string;
  examples: string[];
  designs: { designId: string; name: string; path: string }[];
  workspace: string;
  system: string;
  dims: Record<string, any>;
  materials: Record<string, { name: string; density: number }[]>;
  creatable: string[];
  /** THIRD-PARTY-NOTICES.txt of the installed app (null when run from source without it). */
  notices?: string | null;
}

export type Page = "home" | "design" | "motors" | "flights" | "flightdata" | "targets" | "standards" | "analysis";

export const app = $state({
  session: null as Session | null,
  page: "home" as Page,
  design: null as Design | null,
  selected: "rocket",
  /** A part to preview in the view and the tree: under the pointer in the tree, or where Add part would put one. */
  hover: null as string | null,
  /** The part just selected or added, with when: the view pulses it and its tree row flashes. */
  flash: null as { id: string; at: number; added: boolean } | null,
  /** The last edit the engine refused, shown at the field it was typed in until the next edit or selection. */
  fieldError: null as { component: string; key: string; text: string; hint?: string } | null,
  editor: null as Editor | null,
  model: null as Model | null,
  flight: null as QuickFlight | null,
  flying: false,
  busy: 0,
  toasts: [] as { id: number; text: string; kind: "error" | "info"; issue?: Logged }[],
  /** Physics and output notes on the quick flight of the active configuration, kept current after every edit. */
  flightIssues: [] as Issue[],
  ask: null as null | { title: string; label: string; value: string; resolve: (v: string | null) => void },
  revision: 0, // bumped after every change, so pages reload what they show
  checks: null as Checks | null,
  checking: false,
  targets: null as TargetList | null,
  targeting: false,
});

export interface Check {
  status: string;
  item: string;
  requirement?: string;
  value?: string;
  ref?: string;
}

export interface Checks {
  ruleSet?: string;
  summary?: string;
  checks?: Check[];
  /** What the rules ask for that no simulation can show: for the team to check by hand. */
  manualChecks?: string[];
  error?: string;
  designId: string;
}

export interface Target {
  id: string;
  label: string;
  dim: string | null;
  value: string;
  valueSI: number | null;
  min?: string;
  minSI?: number;
  max?: string;
  maxSI?: number;
  goal?: string;
  goalSI?: number;
  margin?: string;
  marginPercent?: string;
  fromGoal?: string;
  from?: string;
  note?: string;
  status?: string;
}

export interface TargetList {
  simulation?: string;
  summary?: string;
  targets?: Target[];
  PROBLEMS?: string[];
  error?: string;
  designId: string;
}

const AUTO_CHECK = "rocketmaxxing.autoCheck";

/** Whether the rule check runs after every edit (Settings). */
export function autoCheck(): boolean {
  try {
    return localStorage.getItem(AUTO_CHECK) !== "off";
  } catch {
    return true;
  }
}

export function setAutoCheck(on: boolean) {
  try {
    localStorage.setItem(AUTO_CHECK, on ? "on" : "off");
  } catch {
    /* not kept */
  }
  if (on) {
    checkRules();
    checkTargets();
  }
}

let targetSeq = 0;
let targetTimer: ReturnType<typeof setTimeout> | undefined;

/** Compares the active configuration's flight with the team's targets, a moment after the last edit. */
export function checkTargets(delay = 0) {
  const d = app.design;
  if (!d) return;
  clearTimeout(targetTimer);
  const seq = ++targetSeq;
  targetTimer = setTimeout(() => {
    if (seq !== targetSeq) return;
    app.targeting = true;
    call<TargetList>("targets", { designId: d.designId })
      .then((t) => seq === targetSeq && (app.targets = { ...t, designId: d.designId }))
      .catch((e) => seq === targetSeq && (app.targets = { error: quietly(e, "Targets"), designId: d.designId }))
      .finally(() => seq === targetSeq && (app.targeting = false));
  }, delay);
}

/** Sets or clears target limits ("9500 ft", or null to clear) and checks them again. */
export async function setTargets(patch: Record<string, Record<string, string | null>>) {
  await run(async () => {
    await call("app_set_targets", { targets: patch });
    checkTargets();
  });
}

let checkSeq = 0;
let checkTimer: ReturnType<typeof setTimeout> | undefined;

/** Runs the rule check on the active configuration, a moment after the last edit (edits in a row run it once). */
export function checkRules(delay = 0) {
  const d = app.design;
  if (!d) return;
  clearTimeout(checkTimer);
  const seq = ++checkSeq;
  checkTimer = setTimeout(() => {
    if (seq !== checkSeq) return;
    app.checking = true;
    call<Checks>("check_requirements", { designId: d.designId })
      .then((c) => seq === checkSeq && (app.checks = { ...c, designId: d.designId }))
      .catch((e) => seq === checkSeq && (app.checks = { error: quietly(e, "Rule check"), designId: d.designId }))
      .finally(() => seq === checkSeq && (app.checking = false));
  }, delay);
}

let toastId = 0;

export function toast(text: string, kind: "error" | "info" = "info") {
  const id = ++toastId;
  app.toasts.push({ id, text, kind });
  setTimeout(() => (app.toasts = app.toasts.filter((t) => t.id !== id)), kind === "error" ? 8000 : 3500);
}

/** Runs an engine action, showing its error (if any) instead of throwing. */
const PAGES: Record<Page, string> = {
  home: "Start", design: "Design", motors: "Motors", flights: "Flights", flightdata: "Flight data", targets: "Targets",
  standards: "Standards", analysis: "Analysis",
};

/** Puts a failure in the problems list and shows it, labelled with whose problem it is. */
export function report(e: unknown, where = PAGES[app.page]): Logged {
  const l =
    e instanceof ToolError
      ? record(e.issue, where, e.tool, e.args)
      : record(
          { kind: "system", severity: "error", text: "The app hit an error: " + (e instanceof Error ? e.message : String(e)), bug: true, report: e instanceof Error ? e.stack : undefined },
          where,
        );
  const id = ++toastId;
  app.toasts.push({ id, text: l.text, kind: "error", issue: l });
  setTimeout(() => (app.toasts = app.toasts.filter((t) => t.id !== id)), 9000);
  return l;
}

/** A background check's failure: into the problems list without a pop-up (it shows in its own panel). */
export function quietly(e: unknown, where: string): string {
  // A rocket with no motor yet is a stage of the work, not a problem: its panel says so, the problems list stays clear.
  if (e instanceof ToolError && /has no motors/.test(e.issue.text)) return plain(e.issue.text);
  if (e instanceof ToolError) return record(e.issue, where, e.tool, e.args).text;
  const text = e instanceof Error ? e.message : String(e);
  record({ kind: "system", severity: "error", text, bug: true, report: e instanceof Error ? e.stack : undefined }, where);
  return text;
}

/** Runs an action; a failure is reported (and handed to {@code onIssue}, e.g. to mark the form field). */
export async function run<T>(f: () => Promise<T>, where?: string, onIssue?: (l: Logged) => void): Promise<T | undefined> {
  app.busy++;
  try {
    return await f();
  } catch (e) {
    const l = report(e, where);
    onIssue?.(l);
    return undefined;
  } finally {
    app.busy--;
  }
}

/** A small question in the app's own dialog (the browser's prompt() is not available in app windows everywhere). */
export function ask(title: string, label: string, value = ""): Promise<string | null> {
  return new Promise((resolve) => (app.ask = { title, label, value, resolve }));
}

export async function init() {
  const s = await call<Session>("app_session");
  app.session = s;
  units.dims = s.dims;
  await call("app_set_units", { system: units.system }).catch(() => {});
  if (s.designs.length > 0) {
    await load(s.designs[s.designs.length - 1].designId);
  }
}

const RECENT = "rocketmaxxing.recent";

export function recent(): string[] {
  try {
    return JSON.parse(localStorage.getItem(RECENT) ?? "[]");
  } catch {
    return [];
  }
}

function remember(path: string | null) {
  if (!path) return;
  try {
    const list = [path, ...recent().filter((p) => p !== path)].slice(0, 8);
    localStorage.setItem(RECENT, JSON.stringify(list));
  } catch {
    /* not kept */
  }
}

async function load(designId: string) {
  app.selected = "rocket";
  await refresh(designId);
  if (app.design) remember(app.design.path);
}

export async function openExample(name: string) {
  await run(async () => {
    const r = await call("open_design", { example: name });
    await load(r.designId);
  });
}

export async function openPath(path: string) {
  await run(async () => {
    const r = await call("open_design", { path });
    await load(r.designId);
    if (r.conversionWarnings?.length) toast("Converted on opening: " + r.conversionWarnings.join("; "));
  });
}

export async function openDialog() {
  try {
    const r = await call<{ path: string | null }>("app_choose_file", { mode: "open" });
    if (r.path) await openPath(r.path);
  } catch {
    const p = await ask("Open", "File (.ork, .rkt or .CDX1)");
    if (p) await openPath(p.trim());
  }
}

export async function newDesign() {
  const name = await ask("New design", "Rocket name", "Rocket");
  if (!name) return;
  await run(async () => {
    const r = await call("open_design", { newRocketName: name });
    await load(r.designId);
    app.page = "design";
  });
}

/** The formats a design can be written in: its own, and copies for the other programs teams use. */
export const SAVE_FORMATS = { ork: "OpenRocket", rkt: "RockSim", cdx1: "RASAero II" } as const;
export type SaveFormat = keyof typeof SAVE_FORMATS;

/**
 * Saves the design. `as` true asks where (Save as); "rkt" or "cdx1" writes a copy for RockSim or RASAero and leaves
 * the design tied to its own .ork. A name typed with one of those endings in the Save as dialog does the same.
 */
export async function save(as: boolean | SaveFormat = false) {
  const d = app.design;
  if (!d) return;
  const want: SaveFormat = typeof as === "string" ? as : "ork";
  const ending = want === "cdx1" ? ".CDX1" : "." + want;
  await run(async () => {
    let path = as || !d.path ? null : d.path;
    if (!path) {
      const base = (d.path ? d.path.split(/[\\/]/).pop()!.replace(/\.[^.]+$/, "") : d.name) + ending;
      try {
        const r = await call<{ path: string | null }>("app_choose_file", { mode: "save", name: base, types: want === "ork" ? "ork,rkt,cdx1" : want });
        path = r.path;
      } catch {
        path = await ask(want === "ork" ? "Save as" : `Save a copy for ${SAVE_FORMATS[want]}`, `File (${ending})`,
          want === "ork" ? (d.path ?? d.name) + (d.path ? "" : ".ork") : (d.path ?? d.name).replace(/\.ork$/i, "") + ending);
      }
      if (!path) return;
      path = path.trim();
      // A copy keeps the ending asked for; a save takes the one typed (.rkt, .CDX1) or .ork.
      if (want !== "ork") {
        if (!new RegExp(`\\.${want}$`, "i").test(path)) path = path.replace(/\.(ork|rkt|cdx1)$/i, "") + ending;
      } else if (!/\.(ork|rkt|cdx1)$/i.test(path)) path += ".ork";
    }
    const r = await call<{ saved: string; format?: string; copy?: boolean; notes?: string[] }>("save_design", { designId: d.designId, path });
    const file = r.saved.split(/[\\/]/).pop();
    if (r.copy) {
      toast(`Saved a copy for ${r.format}: ${file}. The design itself is still ${d.path ? d.path.split(/[\\/]/).pop() : "not saved as an .ork yet"}.`
        + (r.notes?.length ? ` ${r.format} could not keep: ${r.notes.slice(0, 3).join("; ")}${r.notes.length > 3 ? `; and ${r.notes.length - 3} more` : ""}.` : ""));
    } else {
      toast("Saved " + r.saved);
      remember(r.saved);
    }
    await refresh();
  });
}

let flightSeq = 0;

/** The 3-D model, without the meshes the window already has (again in full if one it counted on is gone). */
async function loadModel(designId: string): Promise<Model> {
  const m = await call<Model>("app_model", { designId, known: knownMeshes() });
  return complete(m.parts) ? m : call<Model>("app_model", { designId });
}

/** Re-reads the design, the 3-D model, the open editor, and (in the background) the quick flight. */
export async function refresh(designId?: string) {
  const id = designId ?? app.design?.designId;
  if (!id) return;
  // The design first: the tree and the page show while the 3-D model (the larger reply) is still on its way.
  const designReply = call<Design>("app_design", { designId: id });
  const modelReply = loadModel(id);
  const design = await designReply;
  app.design = design;
  if (!exists(design.tree, app.selected)) app.selected = "rocket";
  if (designId && app.page === "home") app.page = "design";
  app.model = await modelReply;
  // The editor and the quick flight are asked for together: the flight's request does not wait for the page to
  // finish drawing (calls on a design are answered one at a time, in order, so the editor still comes first).
  const editorReply = call<Editor>("app_component", { designId: id, component: app.selected });
  startQuickFlight(id);
  app.editor = await editorReply;
  app.revision++;
  if (autoCheck()) {
    checkRules(600);
    checkTargets(600);
  }
}

function startQuickFlight(id: string) {
  const seq = ++flightSeq;
  app.flying = true;
  callFull("app_quick_flight", { designId: id })
    .then((r) => {
      if (seq !== flightSeq) return;
      app.flight = r.data as QuickFlight;
      app.flightIssues = r.issues;
    })
    .catch((e) => {
      if (seq !== flightSeq) return;
      app.flight = { error: quietly(e, "Quick flight") };
      app.flightIssues = e instanceof ToolError ? [e.issue] : [];
    })
    .finally(() => seq === flightSeq && (app.flying = false));
}

function exists(n: TreeNode, id: string): boolean {
  return n.id === id || n.children.some((c) => exists(c, id));
}

export function findNode(n: TreeNode | undefined, id: string): TreeNode | null {
  if (!n) return null;
  if (n.id === id) return n;
  for (const c of n.children) {
    const f = findNode(c, id);
    if (f) return f;
  }
  return null;
}

export function parentOf(n: TreeNode, id: string): TreeNode | null {
  for (const c of n.children) {
    if (c.id === id) return n;
    const p = parentOf(c, id);
    if (p) return p;
  }
  return null;
}

export async function select(id: string, added = false) {
  if (!app.design) return;
  if (app.fieldError && app.fieldError.component !== id) app.fieldError = null;
  app.selected = id;
  app.flash = { id, at: performance.now(), added };
  await run(
    async () => {
      app.editor = await call<Editor>("app_component", { designId: app.design!.designId, component: id });
    },
    undefined,
    catchUp,
  );
}

/**
 * An error that means the page is behind the engine (a part deleted or a design closed by an assistant or another
 * window): the design is read again, or left, so the page does not go on showing what is no longer there.
 */
async function catchUp(l: Logged) {
  if (/no longer in the design/.test(l.text)) {
    await refresh().catch(() => {});
  } else if (/no longer open in the engine/.test(l.text)) {
    app.design = null;
    app.selected = "rocket";
    app.fieldError = null;
    app.page = "home";
    app.session = await call<Session>("app_session").catch(() => app.session);
  }
}

async function change(tool: string, args: Record<string, unknown>, onIssue?: (l: Logged) => void) {
  const d = app.design;
  if (!d) return undefined;
  return run(
    async () => {
      const r = await call(tool, { designId: d.designId, ...args });
      await refresh();
      return r;
    },
    undefined,
    (l) => {
      onIssue?.(l);
      catchUp(l);
    },
  );
}

/** Sets one field of the selected part. A value the engine refuses is marked at the field, and undefined comes back. */
export async function editField(key: string, value: unknown) {
  const component = app.selected;
  app.fieldError = null;
  const r = await change("app_edit", { component, key, value }, (l) => (app.fieldError = { component, key, text: l.text, hint: l.fix ?? l.causes[0] }));
  // OpenRocket keeps some values within what the part allows (1 to 8 fins): say so when it did not take what was typed.
  const kept = typeof r?.applied === "string" ? /= (.+?) \(asked for (.+?);/.exec(r.applied) : null;
  if (kept) toast(`Set to ${kept[1]}, not ${kept[2]}: OpenRocket keeps this value within what the part allows.`);
  return r;
}

/** Where a new part of this type goes: inside the selection if it fits there, else after it, else further up. */
export function placement(type: string): { parent: string; index: number } | null {
  const tree = app.design?.tree;
  if (!tree) return null;
  let node: TreeNode | null = findNode(tree, app.selected) ?? tree;
  while (node) {
    if (node.accepts.includes(type)) return { parent: node.id, index: node.children.length };
    const p: TreeNode | null = parentOf(tree, node.id);
    if (p && p.accepts.includes(type)) return { parent: p.id, index: p.children.findIndex((c) => c.id === node!.id) + 1 };
    node = p;
  }
  return null;
}

export async function addPart(type: string) {
  const d = app.design;
  const at = placement(type);
  if (!d || !at) return;
  const r = await change("add_component", {
    type,
    parent: at.parent === "rocket" ? d.tree.name : at.parent,
    index: at.index,
    properties: {},
  });
  app.hover = null;
  if (r?.id) await select(r.id, true);
}

export async function removeSelected() {
  if (!app.design || app.selected === "rocket") return;
  const tree = app.design.tree;
  const p = parentOf(tree, app.selected);
  const name = findNode(tree, app.selected)?.name;
  const r = await change("remove_component", { component: app.selected });
  if (r === undefined || !app.design) return; // refused: the part and the selection stay as they were
  if (name) toast(`Deleted ${name}. ${MAC ? "⌘Z" : "Ctrl+Z"} brings it back.`);
  if (p) await select(exists(app.design.tree, p.id) ? p.id : "rocket");
}

/** Whether the selected part can move up (-1) or down (+1) among its siblings. */
export function canMove(by: number): boolean {
  const tree = app.design?.tree;
  if (!tree || app.selected === "rocket") return false;
  const p = parentOf(tree, app.selected);
  if (!p) return false;
  const i = p.children.findIndex((c) => c.id === app.selected) + by;
  return i >= 0 && i < p.children.length;
}

export async function move(by: number) {
  if (!canMove(by)) return;
  await change("app_move", { component: app.selected, by });
}

/** Moves a part into another part (or the same one), at {@code index} counted before the move. */
export async function moveTo(id: string, parent: string, index: number) {
  const r = await change("app_move", { component: id, parent, index });
  if (r) await select(id);
}

export async function duplicate() {
  if (app.selected === "rocket") return;
  const r = await change("app_duplicate", { component: app.selected });
  if (r?.id) await select(r.id, true);
}

/** The part copied with Ctrl+C: pasted (with what is inside it) where Add part would put a new one of its kind. */
export const clipboard = $state({ id: null as string | null, type: "", name: "" });

export function copySelected() {
  const n = findNode(app.design?.tree, app.selected);
  if (!n || n.id === "rocket") return;
  clipboard.id = n.id;
  clipboard.type = n.type;
  clipboard.name = n.name;
  toast(`Copied ${n.name}. ${MAC ? "⌘V" : "Ctrl+V"} pastes it into the selected part.`);
}

export async function paste() {
  const d = app.design;
  if (!d || !clipboard.id) return;
  if (!exists(d.tree, clipboard.id)) {
    toast(`${clipboard.name} is no longer in this design.`);
    return;
  }
  const at = placement(clipboard.type);
  if (!at) {
    toast(`A ${clipboard.name} cannot go in the selected part. Select a part it fits in.`, "error");
    return;
  }
  const r = await change("app_duplicate", { component: clipboard.id, parent: at.parent, index: at.index });
  if (r?.id) await select(r.id, true);
}

export async function undo() {
  if (app.design?.canUndo) await change("undo", {});
}

export async function redo() {
  if (app.design?.canRedo) await change("redo", {});
}

export async function selectConfig(id: string) {
  await change("app_select_configuration", { configuration: id });
}

/** Any other engine action on the open design, followed by a refresh (motors, deployment, simulations...). */
export async function act(tool: string, args: Record<string, unknown>) {
  return change(tool, args);
}

export async function setSystem(s: "metric" | "imperial") {
  setUnitSystem(s);
  await call("app_set_units", { system: s }).catch(() => {});
}
