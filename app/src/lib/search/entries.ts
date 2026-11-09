// Everything the search can find: the analyses, the pages, the actions in the top bar, parts to add, the parts of the
// open design, and settings. Built when the search opens, so it follows the design and the selection.

import { app, addPart, findNode, newDesign, openDialog, placement, redo, save, select, setSystem, undo, duplicate, copySelected, paste, clipboard, type Page, type TreeNode } from "../state.svelte";
import { CATALOG, type Analysis } from "../analysis/catalog";
import { GUIDES } from "../analysis/guides";
import { analysis } from "../analysis/store.svelte";
import { PART_GROUPS } from "../design/parts";
import { openIssues } from "../issues/issues.svelte";
import { prefs, SCHEMES, setScheme, setTheme } from "../prefs.svelte";
import { assistant, who } from "../assistant/assistant.svelte";
import { keys, MAC } from "../keys";
import { ui, request } from "./ui.svelte";
import { STD_HELP } from "../guide/content";
import { label as keyLabel } from "../analysis/text";
import { partItems } from "../design/partMenu";
import { currentPageItems, type MenuItem } from "../menu/menu.svelte";
import { act, ask } from "../state.svelte";
import { designTour } from "../tour/tours";
import type { Entry } from "./search";

/** The words people use for each analysis that its name does not contain. */
export const ANALYSIS_KEYS: Record<string, string> = {
  check_requirements: "rules compliance DTEG Launch Canada pass fail rail exit thrust to weight stability check requirements",
  design_status: "status todo to do next steps overview what is missing readiness",
  verify_claims: "meeting notes slack notion claims fact check verify numbers",
  advanced_probation: "probation AASI hybrid liquid GLPP Isp static fire",
  aero_analysis: "drag Cd CP centre center of pressure static margin Mach transonic supersonic CNalpha aerodynamics",
  ballast: "nose weight add weight stability CG centre of gravity",
  roll_analysis: "roll spin cant misalignment resonance",
  compare_shapes: "nose cone shape ogive Von Karman Haack conical airfoil fin edge profile",
  recovery_analysis: "opening shock load deployment parachute drogue main shear pins snatch recovery",
  parachute_sizes: "chute size descent rate diameter chart Cd",
  size_parachute: "chute parachute size descent rate diameter CdA",
  deployment_delay_sweep: "late deployment delay backup timing",
  recovery_sections: "sections tethered landing energy kinetic bay fill",
  shear_pins: "shear pins nylon screws 4-40 2-56 separation",
  ejection_charge: "black powder BP charge ejection pressurize ground test",
  opening_shock: "opening load shock snatch force Knacke Cx",
  recovery_bay_fit: "packing fit bay length volume shock cord",
  search_parachutes: "parachute catalog catalogue Fruity Chutes Rocketman buy",
  descent_energy: "landing energy kinetic energy ft-lbf",
  fin_flutter: "flutter divergence fin speed shear modulus thickness",
  fin_fea: "FEA finite element stress Ansys Abaqus CalculiX deflection natural frequency vibration modal structural",
  structural_loads: "airframe loads bending moment axial compression coupler buckling max q structure",
  aero_heating: "heating temperature thermal stagnation",
  compare_materials: "material carbon fiberglass fibreglass G10 aluminum aluminium trade study layup",
  pressure_vessel: "tank COPV burst proof MEOP chamber Barlow",
  add_avionics_bay: "avbay av-bay electronics bay altimeter battery switch sled",
  weather_forecast: "weather forecast wind Open-Meteo launch day temperature",
  rail_buttons: "rail buttons lug rail exit tip-off",
  flight_card: "flight card launch day RSO checklist",
  altimeter_settings: "altimeter deploy altitude Mach lockout backup static port",
  sensor_check: "sensors accelerometer IMU gyro barometer GPS saturate range",
  sensor_data: "flight software HIL test data CSV simulate sensors IMU",
  radio_link: "radio telemetry link budget tracker antenna LoRa range",
  power_budget: "battery power e-match current brownout",
  wind_profile: "winds aloft wind shear wind profile power law",
  monte_carlo: "dispersion landing zone ellipse uncertainty statistics random scatter",
  rank_motors: "motor selection choose motor best motor thrustcurve impulse",
  sweep: "parameter study trade vary what if",
  optimize_fins: "fin design fin optimiser optimizer planform fin size",
  optimize: "optimiser goal seek target apogee minimize minimise mass",
  add_from_link: "shop URL product buy import part link",
  compare_designs: "diff compare changes baseline version git",
  progress_log: "progress weekly update status report milestone PDR CDR",
  history: "undo list edit history changes",
  import_aero_table: "RASAero CFD aero table drag import",
  compare_flight: "flight log altimeter data actual vs predicted calibrate post-flight",
  generate_report: "report design review PDR CDR document",
  export_flight_data: "CSV export flight data MATLAB Excel spreadsheet",
  export_geometry: "STL DXF CAD CFD OpenFOAM export shape waterjet laser cut",
  mass_budget: "mass budget weight weigh-in mass sheet",
};

/** An analysis's searchable text: its group, summary and guide. */
export function analysisText(a: Analysis, group: string): string {
  const g = GUIDES[a.tool];
  return [group, a.blurb ?? "", ...(g ? [...g.needs, ...g.gives, g.how ?? "", g.not ?? ""] : [])].join(" ");
}

const PAGES: { id: Page; title: string; sub: string; keys: string }[] = [
  { id: "home", title: "Start", sub: "Recent files and examples", keys: "home recent examples open file" },
  { id: "design", title: "Design", sub: "The rocket's parts, the 3-D view and the rule check", keys: "parts components edit 3D view tree" },
  { id: "motors", title: "Motors", sub: "Motors, configurations, deployment and staging", keys: "motor configuration delay ignition staging separation deployment" },
  { id: "flights", title: "Flights", sub: "Simulations, launch conditions and plots", keys: "simulation simulate flight apogee plot launch conditions" },
  { id: "targets", title: "Targets", sub: "The team's targets and the competition rules", keys: "goals requirements apogee target" },
  { id: "standards", title: "Standards", sub: "Rule set, launch site, safety factors, ratings and materials", keys: "rule set launch site safety factor pin ratings materials team standards" },
  { id: "analysis", title: "Analysis", sub: "Rule check, recovery, structures, studies", keys: "analyses tools" },
  { id: "flightdata", title: "Flight data", sub: "Altimeter logs and past rockets", keys: "altimeter logs past rockets library" },
];

function go(page: Page) {
  app.page = page;
}

function flatten(n: TreeNode | undefined, out: TreeNode[] = []): TreeNode[] {
  if (!n) return out;
  if (n.id !== "rocket") out.push(n);
  for (const c of n.children) flatten(c, out);
  return out;
}

export function entries(): Entry[] {
  const open = !!app.design;
  const noDesign = open ? undefined : "Open or start a design first";
  const out: Entry[] = [];

  for (const g of CATALOG) {
    for (const a of g.items) {
      out.push({
        kind: "analysis",
        title: a.label,
        sub: a.blurb,
        keys: ANALYSIS_KEYS[a.tool] ?? "",
        text: analysisText(a, g.title),
        off: noDesign,
        run: () => {
          analysis.selected = a.tool;
          go("analysis");
        },
      });
    }
  }

  for (const p of PAGES) {
    out.push({ kind: "page", title: p.title, sub: p.sub, keys: `${p.keys} page tab`, off: p.id === "home" ? undefined : noDesign, run: () => go(p.id) });
  }

  out.push(
    { kind: "action", title: "New design", sub: "Start an empty rocket", keys: "create blank", run: newDesign },
    { kind: "action", title: "Open design", sub: "An .ork or .rkt file", keys: "load file ork rkt rocksim", shortcut: keys("O"), run: openDialog },
    { kind: "action", title: "Save", sub: "Save the design", keys: "write file", shortcut: keys("S"), off: noDesign, run: () => save(false) },
    { kind: "action", title: "Save as", sub: "Save under a new name", keys: "copy file", shortcut: keys("S", true), off: noDesign, run: () => save(true) },
    { kind: "action", title: "Undo", sub: "Undo the last edit", shortcut: keys("Z"), off: open && app.design?.canUndo ? undefined : "Nothing to undo", run: undo },
    { kind: "action", title: "Redo", sub: "Redo the edit undone", shortcut: MAC ? keys("Z", true) : keys("Y"), off: open && app.design?.canRedo ? undefined : "Nothing to redo", run: redo },
    { kind: "action", title: "Guide", sub: "How to use RocketMaxxing: getting started, the pages, which analysis", keys: "help how to instructions tutorial manual start", run: () => (ui.guide = "start") },
    { kind: "action", title: "Design page tour", sub: "A guided tour of the Design page, step by step", keys: "help tour walkthrough introduction how to onboarding", off: noDesign, run: () => designTour() },
    { kind: "action", title: "Which analysis do I need?", sub: "Guide: the analyses by the question they answer", keys: "help guide choose analysis question", run: () => (ui.guide = "analyses") },
    { kind: "action", title: "What are the team standards?", sub: "Guide: what each section holds and which analyses use it", keys: "help guide standards explain", run: () => (ui.guide = "standards") },
    { kind: "action", title: "Problems", sub: "Errors and warnings, and reporting a bug", keys: "errors warnings issues bug report", run: openIssues },
    { kind: "action", title: "Choose a motor", sub: "Motors page", keys: "set motor pick motor", off: noDesign, run: () => go("motors") },
  );

  // The 3-D view and the Design page's pictures.
  const view = (name: string, detail?: string) => () => {
    go("design");
    request(name, detail);
  };
  out.push(
    { kind: "action", title: "Exploded view", sub: "Pull the rocket apart in the view: sections, fins and the parts inside", keys: "explode blow up blown apart parts layout view", off: noDesign, run: view("explode") },
    { kind: "action", title: "Side view", sub: "The rocket from the side, see-through, as OpenRocket draws it", keys: "2d profile view drawing", off: noDesign, run: view("view", "side") },
    { kind: "action", title: "Back view", sub: "Looking along the rocket from behind: the fin layout", keys: "rear end view fins axial", off: noDesign, run: view("view", "back") },
    { kind: "action", title: "3D view", sub: "Turn the rocket in 3-D", keys: "3-d three dimensional rotate orbit perspective", off: noDesign, run: view("view", "3d") },
    { kind: "action", title: "Cut-away view", sub: "The airframe cut open in 3-D to show the inside", keys: "cutaway section inside internal open", off: noDesign, run: view("cutaway") },
    { kind: "action", title: "Fit the rocket in the view", sub: "Zoom to show the whole rocket", keys: "zoom reset center centre frame", off: noDesign, run: view("fit") },
    { kind: "action", title: "Picture or drawing", sub: "Exploded or cut-away 3-D image with a parts list, or the side drawing (SVG)", keys: "image render png svg export screenshot exploded parts list drawing", off: noDesign, run: view("picture") },
    { kind: "action", title: "Flight video", sub: "The selected simulation as an MP4 in your Downloads folder", keys: "animation movie gif mp4 animate", off: noDesign, run: () => (go("flights"), request("video")) },
    { kind: "action", title: "Import a motor file", sub: "A .eng, .rse or zipped motor file into the motor list", keys: "eng rse thrustcurve rasp motor file load", off: noDesign, run: () => (go("motors"), request("motor", "import")) },
    { kind: "action", title: "Your own motor", sub: "A motor from static-fire data or an average thrust", keys: "custom motor static fire test thrust curve homemade research", off: noDesign, run: () => (go("motors"), request("motor", "custom")) },
    { kind: "action", title: "New flight configuration", sub: "Another motor and deployment set", keys: "configuration add create motor set", off: noDesign, run: async () => {
      go("motors");
      const name = await ask("New flight configuration", "Name (empty: named after its motors)", "");
      if (name !== null) await act("flight_configuration", name ? { action: "create", name } : { action: "create" });
    } },
    { kind: "action", title: "New simulation", sub: "Another flight of a configuration with the team's launch site", keys: "add create flight sim", off: noDesign, run: async () => (go("flights"), await act("app_sim_new", {})) },
    { kind: "action", title: "Run all simulations", sub: "Fly every simulation again", keys: "simulate rerun update flights", off: noDesign, run: async () => (go("flights"), await act("app_sim_run", {})) },
    { kind: "action", title: "Compare with an altimeter log", sub: "Flight data: a log against the simulation", keys: "flight log csv altimeter data measured actual", off: noDesign, run: () => go("flightdata") },
    { kind: "action", title: "Past rockets", sub: "Flight data: a folder of old designs and logs", keys: "library history old designs previous years", off: noDesign, run: () => go("flightdata") },
    { kind: "action", title: "Keyboard shortcuts", sub: "Guide: shortcuts and habits worth knowing", keys: "keys hotkeys tips help", run: () => (ui.guide = "tips") },
    { kind: "action", title: "RASAero vs OpenRocket", sub: "Apogee, speed, stability, CD and CP from both models (transonic and supersonic flights)", keys: "rasaero ras aero compare transonic supersonic mach cd cp drag import csv", off: noDesign, run: () => {
      const shown = (app.flight?.maxMach ?? 0) >= 0.8 || !!app.design?.aeroTable;
      if (shown) {
        go("design");
        request("aero");
      } else {
        analysis.selected = "import_aero_table";
        go("analysis");
      }
    } },
    { kind: "action", title: "Line up a part", sub: "Front, middle or back of the part it is in: Properties › Position, or right-click the part", keys: "align alignment position snap center centre", off: noDesign, run: () => (go("design"), request("field", "axialMethod")) },
    { kind: "property", title: "Paint colour", sub: "Properties › Appearance of the selected part", keys: "paint color colour appearance shine gloss", off: noDesign ?? (app.selected === "rocket" ? "Select a part first" : undefined), run: () => (go("design"), request("appearance")) },
    { kind: "action", title: "Fold or unfold panels", sub: "Right-click a panel's title", keys: "collapse expand hide panel", run: () => (ui.guide = "tips") },
  );

  // The selected part's commands and its settings, and the commands of the page on screen.
  const sel = open && app.selected !== "rocket" ? findNode(app.design?.tree, app.selected) : null;
  const fromMenu = (items: (MenuItem | null | undefined | false)[], sub: string, extra = "") => {
    for (const it of items) {
      if (!it || it === "-" || "header" in it) continue;
      out.push({ kind: "action", title: it.label, sub, keys: extra, shortcut: it.shortcut, off: it.off || undefined, run: () => it.run() });
    }
  };
  if (sel && app.page === "design") {
    fromMenu(partItems(sel.id), `${sel.name}`, "part selected component");
    for (const s of app.editor?.id === sel.id ? app.editor.sections : []) {
      for (const f of s.fields) {
        if (f.key === "name") continue;
        out.push({ kind: "property", title: f.label, sub: `${sel.name} › ${s.title}`, keys: `${s.title} ${f.key} ${f.help ?? ""}`, run: () => (go("design"), request("field", f.key)) });
      }
    }
  }
  const PAGE_NAME: Record<string, string> = { home: "Start", design: "Design", motors: "Motors", flights: "Flights", targets: "Targets", standards: "Standards", analysis: "Analysis", flightdata: "Flight data" };
  const known = new Set(out.map((e) => e.title));
  fromMenu(currentPageItems().filter((it) => !(it && typeof it === "object" && "label" in it && known.has(it.label))), `${PAGE_NAME[app.page]} page`);

  // The team standards, each value by name.
  const SECTION_OF: Record<string, string> = { shearPins: "recovery", measuredPackingFactors: "recovery", shearModulus: "structures", youngsModulus: "structures", poissonRatio: "structures", strength: "structures", maxServiceTemperature: "structures", sensors: "electronics", circuits: "electronics" };
  const STD_KEYS: Record<string, string> = {
    shearPins: "pin rating strength nylon 4-40 2-56 shear screw", measuredPackingFactors: "packing factor bay measured",
    shearModulus: "material stiffness G flutter", youngsModulus: "material stiffness E modulus", strength: "material yield ultimate",
    maxServiceTemperature: "material heat temperature limit", sensors: "accelerometer barometer gps imu", circuits: "battery power",
  };
  const SECTION_NAME: Record<string, string> = { launchSite: "Launch site", recovery: "Recovery", structures: "Structures and materials", electronics: "Electronics", competition: "Competition" };
  out.push(
    { kind: "standard", title: "Rule set", sub: "Standards › Competition: the rules the design is checked against", keys: "competition Launch Canada DTEG IREC SA Cup rules ruleset year", off: noDesign, run: () => (go("standards"), request("standard", "competition")) },
  );
  for (const [k, help] of Object.entries(STD_HELP)) {
    const path = k.includes(".") ? k : `${SECTION_OF[k] ?? ""}.${k}`;
    const parts = path.split(".");
    out.push({
      kind: "standard",
      title: keyLabel(parts[parts.length - 1]),
      sub: `Standards › ${[SECTION_NAME[parts[0]] ?? parts[0], ...parts.slice(1, -1).map(keyLabel)].join(" › ")}`,
      keys: `${k} ${STD_KEYS[k] ?? ""} team standards`,
      text: help,
      off: noDesign,
      run: () => (go("standards"), request("standard", path)),
    });
  }

  // The targets.
  const TARGETS: [string, string][] = [
    ["Apogee", "altitude height"], ["Top speed", "max velocity"], ["Top Mach number", "mach"], ["Peak acceleration", "g load"],
    ["Rail exit speed", "rail departure velocity"], ["Thrust to weight", "twr"], ["Lowest stability", "min static margin calibers"],
    ["Highest stability", "over stable"], ["Fin flutter margin", "flutter"], ["Lift-off mass", "weight"], ["Time to apogee", "coast"],
    ["Drogue descent rate", "descent speed"], ["Main descent rate", "descent speed"], ["Landing speed", "ground hit"],
    ["Landing distance", "drift"], ["Flight time", "duration"],
  ];
  for (const [t, k] of TARGETS) {
    out.push({ kind: "target", title: `${t} target`, sub: "Targets: minimum, goal and maximum, with the margin now", keys: `${k} limit goal requirement`, off: noDesign, run: () => go("targets") });
  }
  const part = open && app.selected !== "rocket" ? findNode(app.design?.tree, app.selected)?.name : undefined;
  const pick = part ? undefined : "Select a part first";
  out.push(
    { kind: "action", title: "Duplicate part", sub: part, keys: "copy clone repeat", shortcut: keys("D"), off: noDesign ?? pick, run: () => duplicate() },
    { kind: "action", title: "Copy part", sub: part, keys: "clipboard", shortcut: keys("C"), off: noDesign ?? pick, run: copySelected },
    {
      kind: "action",
      title: "Paste part",
      sub: clipboard.id ? `${clipboard.name}, into the selected part` : undefined,
      keys: "clipboard insert",
      shortcut: keys("V"),
      off: noDesign ?? (clipboard.id ? undefined : "Copy a part first"),
      run: () => paste(),
    },
  );
  out.push({
    kind: "action",
    title: assistant.settings?.enabled ? `Ask ${who()}` : "AI assistant",
    sub: assistant.settings?.enabled ? "About this design" : "Set it up and ask about the design",
    keys: "AI assistant agent robot bot chat claude codex chatgpt",
    run: () => (assistant.open = true),
  });

  // Parts to add, where they would go from the current selection.
  for (const g of PART_GROUPS) {
    for (const [type, label] of g.parts) {
      const at = open ? placement(type) : null;
      const into = at ? (at.parent === "rocket" ? app.design!.tree.name : findNode(app.design?.tree, at.parent)?.name) : null;
      out.push({
        kind: "add",
        title: `Add ${label.toLowerCase()}`,
        sub: into ? `Into ${into}` : g.title,
        keys: `${label} ${type} ${g.title} new part component`,
        off: noDesign ?? (into ? undefined : "Can't go here: select a part it fits in"),
        run: () => {
          go("design");
          addPart(type);
        },
      });
    }
  }
  out.push({
    kind: "add",
    title: "Add avionics bay",
    sub: "Coupler, bulkheads, altimeters, batteries, charges and tracker",
    keys: "avbay electronics bay",
    off: noDesign,
    run: () => {
      analysis.selected = "add_avionics_bay";
      go("analysis");
    },
  });

  // The open design's own parts.
  for (const n of flatten(app.design?.tree)) {
    out.push({
      kind: "part",
      title: n.name,
      sub: n.label,
      keys: n.type,
      run: () => {
        go("design");
        select(n.id);
      },
    });
  }

  const settings = (section: string) => () => (ui.settings = section);
  out.push(
    { kind: "setting", title: "Metric units", sub: "m, kg, N, km/h", keys: "units SI", run: () => setSystem("metric") },
    { kind: "setting", title: "Imperial units", sub: "in, lb, lbf, ft/s", keys: "units US", run: () => setSystem("imperial") },
    { kind: "setting", title: "Units for each quantity", sub: "Settings", keys: "unit preferences", run: settings("Units") },
    { kind: "setting", title: "Light theme", keys: "appearance mode bright", run: () => setTheme("light") },
    { kind: "setting", title: "Dark theme", keys: "appearance mode night", run: () => setTheme("dark") },
    { kind: "setting", title: "Theme follows the system", keys: "appearance mode auto", run: () => setTheme("system") },
    { kind: "setting", title: "Automatic rule check", sub: "Settings", keys: "checks auto", run: settings("Checks") },
    { kind: "setting", title: "AI assistant connection", sub: "Claude, ChatGPT or an API key", keys: "claude codex chatgpt api key connect agent", run: settings("AI assistant") },
  );
  for (const sc of SCHEMES) {
    out.push({
      kind: "setting",
      title: `${sc.name} colour scheme`,
      sub: sc.vision ?? (prefs.scheme === sc.id ? "In use" : "Appearance"),
      keys: "color colour scheme theme appearance",
      run: () => setScheme(sc.id),
    });
  }
  return out;
}
