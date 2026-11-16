// The right-click commands for a part, the same in the parts list and the view.

import { app, act, ask, select, findNode, parentOf, duplicate, copySelected, paste, removeSelected, move, canMove, clipboard, placement } from "../state.svelte";
import { keys } from "../keys";
import { request } from "../search/ui.svelte";
import type { MenuItem } from "../menu/menu.svelte";

/** Focuses the part's name in Properties, ready to type a new one. */
export function rename() {
  requestAnimationFrame(() => {
    const el = document.querySelector('[data-panel="design.props"] .head .name') as HTMLInputElement | null;
    el?.focus();
    el?.select();
  });
}

/** Parts placed one after another (airframe pieces in a stage, pod or booster) have no offset to line up. */
const STACKS = new Set(["Rocket", "AxialStage", "ParallelStage", "PodSet"]);

/** The commands for the part {@code id} (selected first, so they act on it). */
export function partItems(id: string): MenuItem[] {
  const tree = app.design?.tree;
  const n = findNode(tree, id);
  if (!tree || !n) return [];
  if (app.selected !== id) select(id);
  const root = id === "rocket";
  const parent = parentOf(tree, id);
  const at = clipboard.id ? placement(clipboard.type) : null;
  const into = at ? findNode(tree, at.parent)?.name : null;
  const sel = () => app.selected === id;
  const lineUp = !root && parent && !STACKS.has(parent.type);
  const align = (m: string) => () => act("edit_components", { changes: [{ component: id, properties: { axialMethod: m, axialOffset: 0 } }] });
  // A scale beats an estimate: the weighed mass replaces the computed one in every check.
  const weigh = async () => {
    const m = await ask(`Weighed mass of ${n.name}`, "Mass, with its unit (e.g. 245 g or 0.54 lb); empty to use the estimate again", "");
    if (m === null) return;
    const t = m.trim();
    await act("edit_components", { changes: [{ component: id, properties: t ? { massOverridden: true, overrideMass: t } : { massOverridden: false } }] });
  };
  return [
    { header: n.name },
    { label: "Rename", shortcut: "F2", off: root ? "The rocket's name is in Properties" : false, run: () => select(id).then(rename) },
    { label: "Duplicate", shortcut: keys("D"), off: root && "The rocket cannot be duplicated", run: () => sel() && duplicate() },
    { label: "Copy", shortcut: keys("C"), off: root && "The rocket cannot be copied", run: () => sel() && copySelected() },
    {
      label: clipboard.id ? `Paste ${clipboard.name}${into ? " into " + into : ""}` : "Paste",
      shortcut: keys("V"),
      off: !clipboard.id ? "Copy a part first" : !at ? `A ${clipboard.name} cannot go here` : false,
      run: () => paste(),
    },
    "-",
    { label: "Move up", shortcut: "Alt+↑", off: !(sel() && canMove(-1)) && "Already first", run: () => move(-1) },
    { label: "Move down", shortcut: "Alt+↓", off: !(sel() && canMove(1)) && "Already last", run: () => move(1) },
    lineUp && { header: `Line up in ${parent!.name}` },
    lineUp && { label: "At its front", run: align("TOP") },
    lineUp && { label: "In its middle", run: align("MIDDLE") },
    lineUp && { label: "At its back", run: align("BOTTOM") },
    "-",
    { label: root ? "Scale the rocket…" : "Scale this part…", run: () => request("scale", id) },
    { label: n.overridden ? "Weighed mass… (set)" : "Weighed mass…", off: root && "Weigh the parts", run: weigh },
    { label: "Delete", shortcut: "Del", danger: true, off: root && "The rocket cannot be deleted", run: () => sel() && removeSelected() },
  ].filter(Boolean) as MenuItem[];
}
