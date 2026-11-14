import { ToolError, type Issue, type IssueKind } from "../api";
import { explain } from "./explain";

// Everything that went wrong, and what to watch, in one list: whose problem it is (input, output, system, physics),
// where it happened, and for a bug in RocketMaxxing a report to send.

export const KINDS: Record<IssueKind, { label: string; title: string; about: string }> = {
  input: {
    label: "Input",
    title: "Your input",
    about: "Something in what was asked can't be used: a value, a unit, a name. Fix it and run again.",
  },
  physics: {
    label: "Physics",
    title: "Physics",
    about: "The rocket or its flight: the simulation found something physically wrong or risky. The design needs a change.",
  },
  output: {
    label: "Output",
    title: "Our output",
    about: "A result to treat with care: a value that could not be computed, or one from beyond where the model is valid.",
  },
  system: {
    label: "System",
    title: "System",
    about: "Something on this computer (internet, a file, a program) or in RocketMaxxing itself failed, not the design.",
  },
};

export const ORDER: IssueKind[] = ["input", "physics", "output", "system"];

export interface Logged extends Issue {
  n: number;
  at: Date;
  /** The page or action it came from. */
  where: string;
  tool?: string;
  args?: Record<string, unknown>;
  /** What could be causing it, likeliest first, and what to try. */
  causes: string[];
  fix?: string;
  /** The engine's own words, for a report. */
  raw: string;
}

const MAX = 100;
let next = 0;

export const issues = $state({
  log: [] as Logged[],
  open: false,
  /** Entries up to this number have been looked at. */
  seen: 0,
});

export function record(i: Issue, where: string, tool?: string, args?: Record<string, unknown>): Logged {
  // The same error again (a check that fails after every edit) moves to the top instead of filling the list.
  const said = explain(i);
  const same = issues.log.find((l) => l.raw === i.text && l.kind === i.kind && l.tool === tool);
  if (same) issues.log = issues.log.filter((l) => l !== same);
  // An edit's arguments are "key" and "value": the field it is about is marked where it was typed, not named here.
  if (tool === "app_edit") i = { ...i, field: undefined };
  const l: Logged = { ...i, text: said.text, causes: said.causes, fix: said.fix, raw: i.text, n: ++next, at: new Date(), where, tool, args };
  issues.log = [l, ...issues.log].slice(0, MAX);
  return l;
}

export function unseen(): number {
  return issues.log.filter((l) => l.n > issues.seen && l.severity === "error").length;
}

export function openIssues() {
  issues.open = true;
  issues.seen = next;
}

/** Arguments for a report: long values cut, nothing else removed (the user reads it before sending). */
function shortArgs(a: Record<string, unknown> | undefined): string {
  if (!a) return "";
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(a)) {
    const s = typeof v === "string" ? v : JSON.stringify(v);
    out[k] = s && s.length > 200 ? s.slice(0, 200) + "…" : v;
  }
  return JSON.stringify(out, null, 1);
}

/** What to send when RocketMaxxing itself failed: versions, where, the call and the engine's details. */
export function reportText(l: Logged, versions: { version?: string; engineVersion?: string } | null): string {
  return [
    `RocketMaxxing ${versions?.version ?? "?"} (engine ${versions?.engineVersion ?? "?"}), ${navigator.userAgent}`,
    `When: ${l.at.toISOString()}`,
    `Where: ${l.where}${l.tool ? ` (${l.tool})` : ""}`,
    l.id ? `Error ID: ${l.id}` : "",
    `Message: ${l.raw}`,
    l.args ? `Arguments: ${shortArgs(l.args)}` : "",
    l.report ? `Details:\n${l.report}` : "",
  ]
    .filter(Boolean)
    .join("\n");
}

/** Errors in the page's own code count as system errors too. */
export function watchPageErrors() {
  const add = (text: string, stack?: string) =>
    record({ kind: "system", severity: "error", text: "The app hit an error: " + text, bug: true, report: stack }, "App window");
  window.addEventListener("error", (e) => {
    if (/ResizeObserver loop/.test(e.message)) return;
    add(e.message, e.error?.stack);
  });
  window.addEventListener("unhandledrejection", (e) => {
    const r = e.reason;
    if (r instanceof ToolError) record(r.issue, "App window", r.tool, r.args); // a call nothing else reported
    else add(r?.message ?? String(r), r?.stack);
  });
}
