// What the page the team is on shows, in a few lines sent with each message to the assistant: with the numbers already
// in front of it, a question about this page needs no tool call (and no second simulation) to see the same thing.

import { app, type Target } from "../state.svelte";
import { PAGE_NAMES } from "../pageNames";

const MAX = 850;

function targetLine(r: Target, short: boolean): string {
  if (r.invalid) return `${r.label}: the team's value cannot be read (shown on the page)`;
  const lim = [r.min && `min ${r.min}`, r.max && `max ${r.max}`, r.goal && `goal ${r.goal}`].filter(Boolean).join(", ");
  return `${r.label} ${r.value}${lim ? ` (${lim})` : ""}${!short && r.margin ? `, ${r.margin}` : ""}`;
}

/** The Targets page: each target set, missed and close first with their margins, the ones met in short. */
function targets(): string | null {
  const t = app.targets;
  if (!t || t.designId !== app.design?.designId) return null;
  if (t.error) return `Targets could not be checked: ${t.error}`;
  const set = (t.targets ?? []).filter((r) => r.status || r.invalid);
  const group = (st: string) => set.filter((r) => !r.invalid && r.status === st);
  const parts = [
    ["MISSED", group("FAIL"), false],
    ["CLOSE", group("WARN"), false],
    ["met", group("PASS"), true],
    ["goal only", group("INFO").filter((r) => r.goalSI !== undefined), true],
    ["unreadable", set.filter((r) => r.invalid), true],
  ] as const;
  const lines = parts.filter(([, rs]) => rs.length).map(([w, rs, short]) => `${w}: ${rs.map((r) => targetLine(r, short)).join("; ")}`);
  const head = `Targets (simulation "${t.simulation}"${t.stage ? `, numbers for the ${t.stage}` : ""})`;
  return lines.length ? `${head}. ${lines.join(". ")}.` : `${head}: none set.`;
}

/** The rule check in brief: what fails by name, warnings as a count (the assistant can run it for the rest). */
function rules(): string | null {
  const c = app.checks;
  if (!c || c.designId !== app.design?.designId || !c.checks) return null;
  const fails = c.checks.filter((x) => /^FAIL/i.test(x.status));
  const warns = c.checks.filter((x) => /^WARN/i.test(x.status)).length;
  if (!fails.length && !warns) return "Rule check: nothing fails or warns.";
  return `Rule check: ${fails.length ? `FAIL ${fails.map((x) => `${x.item}${x.value ? ` (${x.value})` : ""}`).join("; ")}` : "nothing fails"}${warns ? `; ${warns} warning${warns === 1 ? "" : "s"}` : ""}.`;
}

/** One or two lines about the page the team is looking at, or "" when there is nothing worth sending. */
export function pageContext(): string {
  const where = `On the ${PAGE_NAMES[app.page] ?? app.page} page.`;
  let more: (string | null)[] = [];
  if (app.page === "targets") more = [targets(), rules()];
  else if (app.page === "design" && app.design) {
    more = [app.selected && app.selected !== "rocket" ? `Selected part: ${app.editor?.name ?? app.selected} (id ${app.selected}).` : null];
  }
  const text = [where, ...more.filter(Boolean)].join(" ");
  return text.length > MAX ? text.slice(0, MAX - 3) + "..." : text;
}
