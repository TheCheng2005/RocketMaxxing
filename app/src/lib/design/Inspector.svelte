<script lang="ts">
  import { BookOpen, ChevronDown, TriangleAlert, Search, X, Shapes, Ruler, Flame, Layers, Move, Scale, MessageSquare, Palette, Triangle, Spline, Umbrella, Package, Link, Weight, PenTool, CircleDot, Cylinder } from "@lucide/svelte";

  /** An icon for each kind of section, so they can be told apart at a glance. */
  const SECTION_ICONS: Record<string, typeof Shapes> = {
    Shape: Shapes, Tube: Cylinder, Coupler: Cylinder, "Tube fins": Cylinder, Ring: CircleDot, Bulkhead: CircleDot,
    "Engine block": CircleDot, "Launch lug": Cylinder, "Rail button": CircleDot, "Motor mount": Flame, Material: Layers,
    Position: Move, Overrides: Scale, Comment: MessageSquare, Appearance: Palette, Fins: Triangle, Planform: Ruler,
    "Cross section": Spline, "Fin tabs": Ruler, Outline: PenTool, Canopy: Umbrella, Streamer: Umbrella, "Packed size": Package,
    "Shock cord": Link, Mass: Weight, Pods: Layers, Boosters: Layers, Shoulder: Ruler, Shoulders: Ruler,
  };
  import { app, editField, act, toast } from "../state.svelte";
  import { fmt } from "../units.svelte";
  import { isOpen, toggle, setOpen } from "../panels.svelte";
  import { context, type MenuItem } from "../menu/menu.svelte";
  import { partItems } from "./partMenu";
  import ComponentIcon from "../ComponentIcon.svelte";
  import Field from "./Field.svelte";
  import FinShape from "./FinShape.svelte";
  import CatalogPicker from "./CatalogPicker.svelte";

  // Sections that start folded: rarely changed.
  const FOLDED = new Set(["Overrides", "Comment", "Shoulder", "Shoulders", "Fin tabs", "Appearance"]);
  const FINS = new Set(["TrapezoidFinSet", "EllipticalFinSet", "FreeformFinSet"]);

  let catalog = $state(false);
  // The colour the 3-D view gives the part when it has no paint of its own.
  let shownColor = $derived(app.model?.parts.find((p) => p.id === ed?.id)?.color ?? "#bbbbbb");

  function paint(color: string, shine?: number) {
    act("app_appearance", { component: ed!.id, color, ...(shine === undefined ? {} : { shine }) });
  }

  let ed = $derived(app.editor);
  let sections = $derived(ed ? ed.sections.filter((s) => s.title !== "General" && s.title !== "Design") : []);
  let rocketFields = $derived(ed?.type === "Rocket" ? (ed.sections.find((s) => s.title === "Design")?.fields ?? []).filter((f) => f.key !== "name") : []);
  let name = $state("");
  $effect(() => {
    name = ed?.name ?? "";
  });

  // A filter over every setting of the part, folded sections included: "wall" finds the wall thickness.
  let find = $state("");
  let found = $derived.by(() => {
    const q = find.trim().toLowerCase();
    if (!q || !ed) return null;
    const out: { section: string; f: import("../state.svelte").FieldDef }[] = [];
    for (const s of ed.sections) for (const f of s.fields) if (f.key !== "name" && (f.label.toLowerCase().includes(q) || s.title.toLowerCase().includes(q))) out.push({ section: s.title, f });
    return out;
  });

  // Asked for from the search: show one setting of the selected part, ready to type.
  import { ui as searchUi, take } from "../search/ui.svelte";
  $effect(() => {
    void searchUi.request;
    const r = take("field", "appearance");
    if (!r || !ed) return;
    if (r.name === "appearance") {
      find = "";
      setOpen("prop.Appearance", true);
      requestAnimationFrame(() => {
        const sw = document.querySelector('[data-panel="design.props"] .swatch') as HTMLElement | null;
        sw?.scrollIntoView({ block: "center" });
        sw?.focus();
      });
      return;
    }
    const f = ed.sections.flatMap((s) => s.fields).find((x) => x.key === r.detail);
    if (!f) return;
    find = f.label;
    requestAnimationFrame(() => (document.getElementById("f-" + f.key) as HTMLElement | null)?.focus());
  });

  // Lining the part up in its parent in one step (one undo): where it is measured from, and no offset.
  const ALIGN: [string, string, string][] = [
    ["TOP", "Front", "Its front at the front of the part it is in"],
    ["MIDDLE", "Middle", "Centred in the part it is in"],
    ["BOTTOM", "Back", "Its back at the back of the part it is in"],
  ];
  let position = $derived(ed?.sections.find((s) => s.title === "Position")?.fields ?? []);
  let method = $derived(position.find((f) => f.key === "axialMethod")?.value as string | undefined);
  let offset = $derived(position.find((f) => f.key === "axialOffset")?.value as number | undefined);
  // Right-click in Properties: find a setting, fold or unfold the sections, the catalogue; then the part's commands.
  function titles(): string[] {
    return [...(ed?.sections.map((s) => s.title) ?? []), "Outline", "Appearance"];
  }
  function panelItems(): MenuItem[] {
    if (!ed) return [];
    return [
      { header: "Properties" },
      { label: "Find a setting", run: () => (document.querySelector('[data-panel="design.props"] .find input') as HTMLElement | null)?.focus() },
      { label: "Fold all sections", run: () => titles().forEach((t) => setOpen("prop." + t, false)) },
      { label: "Unfold all sections", run: () => titles().forEach((t) => setOpen("prop." + t, true)) },
      ed.presetType ? { label: "Catalogue parts…", run: () => (catalog = true) } : null,
      ed.paint ? { label: "Default colour", run: () => paint("") } : null,
    ].filter(Boolean) as MenuItem[];
  }
  function allItems(): MenuItem[] {
    return ed ? [...panelItems(), "-", ...partItems(ed.id)] : [];
  }

  function align(m: string) {
    act("edit_components", { changes: [{ component: ed!.id, properties: { axialMethod: m, axialOffset: 0 } }] });
  }
</script>

{#if ed}
  <div class="head" use:context={allItems}>
    <ComponentIcon type={ed.type} size={34} />
    <div class="who">
      <input
        class="name"
        bind:value={name}
        onblur={() => {
          if (!name.trim()) {
            name = ed.name;
            toast("A part needs a name: the old one was kept.", "error");
          } else if (name !== ed.name) editField("name", name);
        }}
        onkeydown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()}
        aria-label="Name"
      />
      <div class="type">
        {ed.label}{#if ed.componentMass !== undefined && ed.type !== "Rocket"}, {fmt(ed.componentMass, "MASS")}{#if ed.mass !== undefined && Math.abs(ed.mass - ed.componentMass) > 1e-6}&nbsp;({fmt(ed.mass, "MASS")} with contents){/if}{/if}
      </div>
    </div>
  </div>
  {#if ed.warning}
    <div class="warning"><TriangleAlert size={14} /> <span>{ed.warning}</span></div>
  {/if}
  {#if ed.presetType}
    <div class="catalog">
      <span class="muted">{ed.preset ? "Catalogue part: " + ed.preset : "Not a catalogue part"}</span>
      <button class="btn" onclick={() => (catalog = true)}><BookOpen size={14} /> Catalogue…</button>
    </div>
  {/if}
  {#if ed.type !== "Rocket"}
    <div class="find">
      <Search size={13} />
      <input bind:value={find} placeholder="Find a setting (e.g. thickness)" aria-label="Find a setting" onkeydown={(e) => e.key === "Escape" && (find = "")} />
      {#if find}<button class="clear" onclick={() => (find = "")} aria-label="Clear"><X size={13} /></button>{/if}
    </div>
  {/if}
  <div class="sections" use:context={allItems}>
    {#if found}
      <div class="fields">
        {#each found as { section, f } (section + f.key)}
          <div class="from">{section}</div>
          <Field {f} more={allItems} />
        {:else}
          <p class="muted none">No setting of a {ed.label.toLowerCase()} matches “{find}”.</p>
        {/each}
      </div>
    {:else}
    {#if rocketFields.length}
      <div class="fields">
        {#each rocketFields as f (f.key)}<Field {f} more={allItems} />{/each}
      </div>
    {/if}
    {#if FINS.has(ed.type)}
      {@render folding("Outline", fin)}
    {/if}
    {#each sections as s (s.title)}
      {@const id = "prop." + s.title}
      {@const open = isOpen(id, !FOLDED.has(s.title))}
      <section class="sec">
        <button class="st" onclick={() => toggle(id, !FOLDED.has(s.title))} aria-expanded={open}>
          {@render sectionIcon(s.title)}
          <span class="stt">{s.title}</span>
          <span class="chev" class:shut={!open}><ChevronDown size={14} /></span>
        </button>
        {#if open}
          <div class="fields">
            {#each s.fields as f (f.key)}<Field {f} more={allItems} />{/each}
            {#if s.title === "Position"}{@render lineUp()}{/if}
          </div>
        {/if}
      </section>
    {/each}
    {#if ed.type !== "Rocket"}
      {@render folding("Appearance", look)}
    {/if}
    {/if}
  </div>
  {#if catalog}<CatalogPicker component={ed.id} close={() => (catalog = false)} />{/if}
{/if}

{#snippet folding(title: string, body: import("svelte").Snippet)}
  {@const id = "prop." + title}
  {@const open = isOpen(id, !FOLDED.has(title))}
  <section class="sec">
    <button class="st" onclick={() => toggle(id, !FOLDED.has(title))} aria-expanded={open}>
      {@render sectionIcon(title)}
      <span class="stt">{title}</span>
      <span class="chev" class:shut={!open}><ChevronDown size={14} /></span>
    </button>
    {#if open}<div class="fields">{@render body()}</div>{/if}
  </section>
{/snippet}

{#snippet sectionIcon(title: string)}
  {@const I = SECTION_ICONS[title] ?? CircleDot}
  <span class="si"><I size={14} /></span>
{/snippet}

{#snippet lineUp()}
  {#if method === "AFTER"}
    <p class="muted small note">An airframe piece follows the one above it. To change the order, drag it in the parts list.</p>
  {:else if method}
    <div class="row">
      <span class="label">Line up</span>
      <div class="align" role="group" aria-label="Line up in the part it is in">
        {#each ALIGN as [m, label, title] (m)}
          <button class="btn" class:on={method === m && Math.abs(offset ?? 1) < 1e-9} {title} onclick={() => align(m)}>{label}</button>
        {/each}
      </div>
    </div>
    <p class="muted small note">Or drag it along the rocket in the view.</p>
  {/if}
{/snippet}

{#snippet fin()}
  {#key ed!.id}<FinShape component={ed!.id} type={ed!.type} />{/key}
{/snippet}

{#snippet look()}
  <div class="row">
    <span class="label">Paint</span>
    <div class="control">
      <input type="color" class="swatch" value={ed!.paint ?? shownColor} onchange={(e) => paint(e.currentTarget.value)} aria-label="Paint colour" />
      {#if ed!.paint}<button class="link" onclick={() => paint("")}>Default</button>{:else}<span class="muted small">default</span>{/if}
    </div>
  </div>
  <div class="row">
    <span class="label">Shine</span>
    <input type="range" min="0" max="1" step="0.05" value={ed!.shine ?? 0.3} disabled={!ed!.paint}
      onchange={(e) => paint(ed!.paint!, Number(e.currentTarget.value))} aria-label="Shine" />
  </div>
{/snippet}

<style>
  .head {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 14px;
    border-bottom: 1px solid var(--line);
  }
  .who {
    min-width: 0;
    flex: 1;
  }
  .name {
    width: 100%;
    font-size: 14px;
    font-weight: 600;
    border: 1px solid transparent;
    border-radius: var(--radius);
    padding: 1px 4px;
    margin-left: -5px;
    background: transparent;
  }
  .name:hover {
    border-color: var(--line);
  }
  .name:focus {
    outline: none;
    border-color: var(--select);
    background: var(--surface);
  }
  .type {
    font-size: 12px;
    color: var(--ink-3);
  }
  .warning {
    display: flex;
    gap: 6px;
    align-items: flex-start;
    padding: 6px 10px;
    border-bottom: 1px solid var(--line);
    background: var(--warn-soft);
    font-size: 12px;
  }
  .warning :global(svg) {
    flex: none;
    margin-top: 2px;
    color: var(--warn);
  }
  .sections {
    flex: 1;
    overflow: auto;
    padding-bottom: 10px;
  }
  .sec {
    border-bottom: 1px solid var(--line);
  }
  .st {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 10px;
    border: 0;
    background: transparent;
    padding: 10px 12px;
    font-weight: 600;
    color: var(--ink);
    text-align: left;
  }
  .stt {
    flex: 1;
  }
  .si {
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    border-radius: 6px;
    background: var(--surface-2);
    color: var(--ink-2);
  }
  .st:hover {
    background: var(--surface-2);
  }
  .chev {
    display: flex;
    color: var(--ink-3);
    transition: transform 0.1s;
  }
  .chev.shut {
    transform: rotate(-90deg);
  }
  .fields {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 0 14px 14px 46px;
  }
  .catalog {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 10px 14px;
    border-bottom: 1px solid var(--line);
    font-size: 12px;
  }
  .catalog .btn {
    flex: none;
  }
  .row {
    display: grid;
    grid-template-columns: 44% 1fr;
    align-items: center;
    gap: 6px;
    min-height: var(--control);
  }
  .row .label {
    color: var(--ink-2);
  }
  .control {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .swatch {
    width: 44px;
    height: 24px;
    padding: 0;
    border: 1px solid var(--line);
    border-radius: 4px;
    background: transparent;
  }
  .link {
    border: 0;
    background: transparent;
    color: var(--select);
    padding: 0;
  }
  .small {
    font-size: 12px;
  }
  .find {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 12px 14px 6px;
    padding: 0 6px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    color: var(--ink-3);
    background: var(--surface);
  }
  .find:focus-within {
    border-color: var(--select);
  }
  .find input {
    flex: 1;
    min-width: 0;
    border: 0;
    outline: 0;
    height: 30px;
    background: transparent;
    color: var(--ink);
  }
  .clear {
    display: grid;
    border: 0;
    background: transparent;
    color: var(--ink-3);
    padding: 2px;
  }
  .from {
    margin-top: 4px;
    font-size: 11px;
    color: var(--ink-3);
    text-transform: uppercase;
    letter-spacing: 0.03em;
  }
  .none {
    margin: 6px 0;
  }
  .align {
    display: flex;
    gap: 4px;
  }
  .align .btn {
    flex: 1;
    justify-content: center;
    padding: 0 4px;
  }
  .align .btn.on {
    background: var(--select-soft);
    border-color: var(--select);
  }
  .note {
    margin: 2px 0 0;
  }
  .sections > .fields {
    padding: 12px 14px;
    border-bottom: 1px solid var(--line);
  }
</style>
