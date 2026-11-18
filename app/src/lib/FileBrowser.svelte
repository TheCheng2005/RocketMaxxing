<script lang="ts">
  import { onMount, tick } from "svelte";
  import { Folder, File as FileIcon, ArrowUp, HardDrive, House, Monitor, FileText, Download, Cloud, FolderOpen } from "@lucide/svelte";
  import Modal from "./Modal.svelte";
  import { call, usePagePicker } from "./api";
  import { MAC } from "./keys";

  // The app's own file browser, in place of the system's open and save dialogs on a Mac: there, the system's panel
  // runs a modal loop on the thread the app's window shares with Java, and the whole app froze when Open or Import
  // was clicked (1.6.0, 1.6.1). Every "app_choose_file" comes here instead (api.ts); elsewhere the system's dialog
  // stays. localStorage "rocketmaxxing.pagePicker" = "1" turns it on anywhere (for testing).

  interface Entry {
    name: string;
    size?: number;
    modified?: number;
  }
  interface Listing {
    path: string;
    parent: string | null;
    separator: string;
    folders: Entry[];
    files: Entry[];
    more: boolean;
    places: { label: string; path: string }[];
  }
  type Mode = "open" | "save" | "folder";

  let req = $state<null | { mode: Mode; types: string[]; name: string; resolve: (r: { path: string | null }) => void }>(null);
  let list = $state<Listing | null>(null);
  let error = $state("");
  let typed = $state("");
  let name = $state("");
  let picked = $state<string | null>(null);
  let replace = $state(false);
  let showAll = $state(false);
  let nameBox: HTMLInputElement | undefined = $state();

  const LAST = "rocketmaxxing.browserFolder";
  const DESIGN_TYPES = "ork,rmx,rkt,cdx1";

  function on(): boolean {
    try {
      if (localStorage.getItem("rocketmaxxing.pagePicker") === "1") return true;
    } catch {
      /* storage unavailable */
    }
    return MAC;
  }

  onMount(() => {
    if (!on()) return;
    usePagePicker(
      (args) =>
        new Promise((resolve) => {
          const mode = (args.mode as Mode) ?? "open";
          const types = String(args.types ?? (mode === "folder" ? "" : DESIGN_TYPES)).toLowerCase().split(/[,\s]+/).filter(Boolean);
          req = { mode, types, name: String(args.name ?? ""), resolve };
          name = req.name;
          picked = null;
          replace = false;
          showAll = false;
          let start: string | undefined;
          try {
            start = localStorage.getItem(LAST + "." + mode) ?? localStorage.getItem(LAST) ?? undefined;
          } catch {
            /* none */
          }
          go(start);
        }),
    );
    return () => usePagePicker(null);
  });

  async function go(path?: string) {
    error = "";
    try {
      list = await call<Listing>("app_list_folder", path ? { path } : {});
      typed = list.path;
      picked = null;
      replace = false;
      await tick();
      if (req?.mode === "save") nameBox?.focus();
    } catch (e: any) {
      error = String(e?.message ?? e);
      if (!list && path) go(); // the remembered folder has gone: the designs folder
    }
  }

  const join = (dir: string, n: string) => (dir.endsWith(list?.separator ?? "/") ? dir + n : dir + (list?.separator ?? "/") + n);
  const ext = (n: string) => (n.includes(".") ? n.slice(n.lastIndexOf(".") + 1).toLowerCase() : "");
  const fits = (n: string) => !req || req.mode === "folder" || !req.types.length || req.types.includes("*") || req.types.includes(ext(n));
  let files = $derived((list?.files ?? []).filter((f) => showAll || fits(f.name)));
  let hidden = $derived((list?.files.length ?? 0) - files.length);

  function done(path: string | null) {
    const r = req;
    if (!r) return;
    if (path && list) {
      try {
        localStorage.setItem(LAST + "." + r.mode, list.path);
        localStorage.setItem(LAST, list.path);
      } catch {
        /* not kept */
      }
    }
    req = null;
    r.resolve({ path });
  }

  function choose() {
    if (!req || !list) return;
    if (req.mode === "folder") return done(picked ? join(list.path, picked) : list.path);
    if (req.mode === "open") return picked ? done(join(list.path, picked)) : undefined;
    let n = name.trim();
    if (!n) return;
    // the first of the asked types, when the name has none of them
    if (req.types.length && !req.types.includes(ext(n)) && req.types[0] !== "*") n += "." + req.types[0];
    if (list.files.some((f) => f.name === n) && !replace) {
      name = n;
      replace = true;
      return;
    }
    done(join(list.path, n));
  }

  function typedGo() {
    const t = typed.trim();
    if (!t) return;
    go(t.startsWith("~") && list ? (list.places.find((p) => p.label === "Home")?.path ?? "") + t.slice(1) : t);
  }

  const icon = (label: string) =>
    ({ Designs: FolderOpen, Home: House, Desktop: Monitor, Documents: FileText, Downloads: Download, "iCloud Drive": Cloud })[label] ?? HardDrive;
  const size = (b?: number) => (b === undefined ? "" : b < 1024 ? `${b} B` : b < 1048576 ? `${(b / 1024).toFixed(0)} KB` : `${(b / 1048576).toFixed(1)} MB`);
  const when = (t?: number) => (t ? new Date(t).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" }) : "");
  let title = $derived(req?.mode === "save" ? "Save" : req?.mode === "folder" ? "Choose a folder" : "Open");
</script>

{#if req}
  <Modal {title} close={() => done(null)} width={820}>
    <div class="fb">
      <nav class="places" aria-label="Places">
        {#each list?.places ?? [] as p (p.path)}
          {@const I = icon(p.label)}
          <button class:on={list?.path === p.path} onclick={() => go(p.path)} title={p.path}><I size={14} /> {p.label}</button>
        {/each}
      </nav>
      <div class="main">
        <div class="bar">
          <button class="btn small" disabled={!list?.parent} onclick={() => list?.parent && go(list.parent)} title="Up one folder" aria-label="Up one folder"><ArrowUp size={14} /></button>
          <input class="input path" bind:value={typed} aria-label="Folder" spellcheck="false" onkeydown={(e) => e.key === "Enter" && typedGo()} />
        </div>
        {#if error}<p class="err">{error}</p>{/if}
        <div class="list" role="listbox" aria-label="Files">
          {#each list?.folders ?? [] as f (f.name)}
            <button class="row" class:sel={picked === f.name && req.mode === "folder"} role="option" aria-selected={picked === f.name}
              onclick={() => (req?.mode === "folder" ? (picked = f.name) : go(join(list!.path, f.name)))} ondblclick={() => go(join(list!.path, f.name))}>
              <Folder size={14} /> <span class="n">{f.name}</span><span></span><span></span>
            </button>
          {/each}
          {#if req.mode !== "folder"}
            {#each files as f (f.name)}
              <button class="row" class:sel={picked === f.name} class:dim={!fits(f.name)} role="option" aria-selected={picked === f.name}
                onclick={() => { picked = f.name; if (req?.mode === "save") { name = f.name; replace = false; } }}
                ondblclick={() => { picked = f.name; if (req?.mode === "save") name = f.name; choose(); }}>
                <FileIcon size={14} /> <span class="n">{f.name}</span><span class="m">{size(f.size)}</span><span class="m">{when(f.modified)}</span>
              </button>
            {/each}
          {/if}
          {#if list && !list.folders.length && (req.mode === "folder" || !files.length)}<p class="muted empty">Nothing here{hidden ? " of the right type" : ""}.</p>{/if}
          {#if list?.more}<p class="muted empty">Only the first 2000 shown.</p>{/if}
        </div>
        <div class="foot">
          {#if req.mode === "save"}
            <label class="name">Name <input class="input" bind:this={nameBox} bind:value={name} spellcheck="false" oninput={() => (replace = false)} onkeydown={(e) => e.key === "Enter" && choose()} /></label>
          {:else if req.mode === "open" && req.types.length}
            <label class="all"><input type="checkbox" bind:checked={showAll} /> Show every file{hidden ? ` (${hidden} more)` : ""}</label>
          {/if}
          <span class="grow"></span>
          {#if replace}<span class="warn">{name} is there already.</span>{/if}
          <button class="btn" onclick={() => done(null)}>Cancel</button>
          <button class="btn primary" disabled={req.mode === "open" ? !picked : req.mode === "save" ? !name.trim() : !list} onclick={choose}>
            {req.mode === "save" ? (replace ? "Replace" : "Save") : req.mode === "folder" ? (picked ? `Choose ${picked}` : "Choose this folder") : "Open"}
          </button>
        </div>
      </div>
    </div>
  </Modal>
{/if}

<style>
  .fb {
    display: grid;
    grid-template-columns: 170px minmax(0, 1fr);
    gap: 12px;
    height: min(520px, calc(100vh - 140px));
  }
  .places {
    display: flex;
    flex-direction: column;
    gap: 2px;
    overflow: auto;
    border-right: 1px solid var(--line);
    padding-right: 8px;
  }
  .places button {
    display: flex;
    align-items: center;
    gap: 7px;
    text-align: left;
    padding: 5px 8px;
    border: 0;
    border-radius: 5px;
    background: none;
    color: var(--ink);
    cursor: pointer;
    font-size: calc(13px * var(--text));
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .places button:hover {
    background: var(--surface-2);
  }
  .places button.on {
    background: var(--select-soft);
    font-weight: 600;
  }
  .main {
    display: flex;
    flex-direction: column;
    gap: 8px;
    min-width: 0;
  }
  .bar {
    display: flex;
    gap: 6px;
  }
  .path {
    flex: 1;
    min-width: 0;
    font-family: ui-monospace, Menlo, Consolas, monospace;
    font-size: calc(12px * var(--text));
  }
  .list {
    flex: 1;
    overflow: auto;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    padding: 3px;
  }
  .row {
    display: grid;
    grid-template-columns: 16px minmax(0, 1fr) 70px 100px;
    gap: 8px;
    align-items: center;
    width: 100%;
    text-align: left;
    padding: 4px 8px;
    border: 0;
    border-radius: 4px;
    background: none;
    color: var(--ink);
    cursor: default;
    font-size: calc(13px * var(--text));
  }
  .row:hover {
    background: var(--surface-2);
  }
  .row.sel {
    background: var(--select);
    color: #fff;
  }
  .row.sel .m {
    color: #fff;
  }
  .row.dim {
    color: var(--ink-3);
  }
  .n {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .m {
    color: var(--ink-3);
    font-size: calc(11.5px * var(--text));
    text-align: right;
    white-space: nowrap;
  }
  .empty {
    padding: 10px;
    margin: 0;
  }
  .foot {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }
  .name {
    display: flex;
    align-items: center;
    gap: 6px;
    flex: 1;
    min-width: 220px;
  }
  .name .input {
    flex: 1;
  }
  .all {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: calc(12.5px * var(--text));
    color: var(--ink-2);
  }
  .grow {
    flex: 1;
  }
  .warn {
    color: var(--warn);
    font-size: calc(12.5px * var(--text));
  }
  .err {
    color: var(--bad);
    margin: 0;
    font-size: calc(12.5px * var(--text));
  }
  @media (max-width: 640px) {
    .fb {
      grid-template-columns: 1fr;
    }
    .places {
      flex-direction: row;
      flex-wrap: wrap;
      border-right: 0;
      border-bottom: 1px solid var(--line);
      padding: 0 0 6px;
    }
  }
</style>
