// The engine client: every action is a tool call to the local RocketMaxxing server (the same tools the MCP server
// offers Claude). The token comes from the address the launcher opened and is never sent anywhere else.

const KEY = "rocketmaxxing.token";

/** Designs to open, passed in the window's address (a double-clicked file, a second launch). */
export const launchFiles: string[] = new URLSearchParams(location.hash.replace(/^#/, "")).getAll("open");

/**
 * Designs handed to the window while it is open: RocketMaxxing's window loads the page's address again with them, and
 * as only the part after "#" changes, the page stays. Returns them and clears the address.
 */
export function handedFiles(): string[] {
  const files = new URLSearchParams(location.hash.replace(/^#/, "")).getAll("open");
  history.replaceState(null, "", location.pathname + location.search);
  return files;
}

function readToken(): string {
  const hash = new URLSearchParams(location.hash.replace(/^#/, ""));
  const t = hash.get("token");
  if (t) {
    try {
      sessionStorage.setItem(KEY, t);
    } catch {
      /* private mode: keep it in memory only */
    }
    history.replaceState(null, "", location.pathname + location.search);
    return t;
  }
  try {
    return sessionStorage.getItem(KEY) ?? "";
  } catch {
    return "";
  }
}

const token = readToken();
let nextId = 1;

/** Where the page can load a picture or video the engine wrote in the designs folder. */
export function fileUrl(path: string): string {
  return `/file?token=${encodeURIComponent(token)}&path=${encodeURIComponent(path)}&v=${Date.now()}`;
}

/**
 * Whose problem something is: what was asked (input), a result to treat with care (output), the computer or
 * RocketMaxxing itself (system), or the rocket's flight (physics). The engine tags each error and result note.
 */
export type IssueKind = "input" | "output" | "system" | "physics";

export interface Issue {
  kind: IssueKind;
  severity: "error" | "warning";
  text: string;
  /** The argument it is about, for the form field to mark. */
  field?: string;
  /** An engine bug: its ID (also in the engine's log) and the details for a report. */
  id?: string;
  bug?: boolean;
  report?: string;
}

const ISSUES = "io.github.openrocketmcp/issues";

function issuesOf(result: any): Issue[] {
  const list = result?._meta?.[ISSUES];
  return Array.isArray(list) ? list : [];
}

export class ToolError extends Error {
  issue: Issue;
  tool: string;
  args: Record<string, unknown>;

  constructor(message: string, issue?: Issue, tool = "", args: Record<string, unknown> = {}) {
    super(message);
    this.issue = issue ?? { kind: "system", severity: "error", text: message };
    this.tool = tool;
    this.args = args;
  }
}

/** The error of a failed call, with the engine's issue (whose problem it is, and the field). */
function failure(name: string, args: Record<string, unknown>, result: any): ToolError {
  const text = (result?.content?.[0]?.text ?? "").replace(/^Error:\s*/, "");
  const issue = issuesOf(result)[0] ?? { kind: "input", severity: "error", text };
  return new ToolError(text, { ...issue, text: issue.text || text }, name, args);
}

/** JSON as the engine writes it; a bare NaN or Infinity in a value position (never valid JSON) reads as null. */
function parse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return JSON.parse(text.replace(/([:\[,]\s*)-?(?:NaN|Infinity)(?=\s*[,\]}])/g, "$1null"));
  }
}

async function rpc(method: string, params: unknown, keepalive = false): Promise<any> {
  let res: Response;
  try {
    res = await fetch("rpc", {
      method: "POST",
      keepalive,
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ jsonrpc: "2.0", id: nextId++, method, params }),
    });
  } catch {
    throw new ToolError("Lost the connection to the RocketMaxxing engine. Restart the app.");
  }
  if (res.status === 401) {
    throw new ToolError("This window is not connected. Restart RocketMaxxing.");
  }
  if (!res.ok) {
    throw new ToolError(`The engine answered ${res.status}.`, { kind: "system", severity: "error", text: `The engine answered ${res.status}.`, bug: true });
  }
  const msg = await res.json();
  if (msg.error) {
    const text = msg.error.message ?? "Engine error";
    throw new ToolError(text, { kind: "system", severity: "error", text, bug: true });
  }
  return msg.result;
}

/** Calls a tool; returns its JSON result (or text), or throws ToolError with the engine's explanation. */
export async function call<T = any>(name: string, args: Record<string, unknown> = {}): Promise<T> {
  const result = await rpc("tools/call", { name, arguments: args });
  const text: string = result?.content?.[0]?.text ?? "";
  if (result?.isError) {
    throw failure(name, args, result);
  }
  try {
    return parse(text) as T;
  } catch {
    return text as unknown as T;
  }
}

/** A last call as the window closes: the browser finishes sending it after the page is gone. Nothing comes back. */
export function callOnExit(name: string, args: Record<string, unknown>): void {
  rpc("tools/call", { name, arguments: args }, true).catch(() => {});
}

export interface ToolInfo {
  name: string;
  title: string;
  description: string;
  inputSchema: { properties?: Record<string, any>; required?: string[] };
  annotations?: { readOnlyHint?: boolean };
}

let toolList: Promise<ToolInfo[]> | null = null;

/** Every tool the engine offers, with its input definition (fetched once). */
export function tools(): Promise<ToolInfo[]> {
  toolList ??= rpc("tools/list", {}).then((r) => r.tools as ToolInfo[]);
  return toolList;
}

/** Calls a tool and also returns the pictures it attached (plots) and its notes (physics, output). */
export async function callFull(
  name: string,
  args: Record<string, unknown> = {},
): Promise<{ data: any; images: { src: string }[]; issues: Issue[] }> {
  const result = await rpc("tools/call", { name, arguments: args });
  const text: string = result?.content?.[0]?.text ?? "";
  if (result?.isError) {
    throw failure(name, args, result);
  }
  let data: any;
  try {
    data = parse(text);
  } catch {
    data = text;
  }
  const images = (result?.content ?? [])
    .filter((c: any) => c.type === "image")
    .map((c: any) => ({ src: `data:${c.mimeType};base64,${c.data}` }));
  return { data, images, issues: issuesOf(result) };
}

export const connected = token !== "";
