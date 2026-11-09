# Desktop app plan: RocketMaxxing

A desktop application for competition rocket teams that works offline and needs no AI subscription. It covers what
OpenRocket does and adds the engineering this server already does: rule checks, recovery, structures, avionics,
dispersion, flight data and design history, behind a modern interface.

Status: built through tier 3 of the new functions below, plus a round on ease of use. The app redoes OpenRocket's
three main tabs (design, motors and configuration, simulations) on the engine and adds the engine's analyses, the
team's targets, flight data, the missing editors, installers and an optional assistant; see
[ROCKETMAXXING.md](ROCKETMAXXING.md). The project's team uses it, and it is planned to go to other teams by the end
of 2026 once their feedback is in. Decided: the name is **RocketMaxxing** (the repository is
`TheCheng2005/RocketMaxxing`) and the front-end is **Svelte with three.js**. The app runs in its own window
(Chromium through JCEF) on all three systems, and the validation work has its first real flight
([WORKED_EXAMPLE.md](WORKED_EXAMPLE.md)). Next: internal testing by the team, then Phase 4.
Decisions still open are marked **Decide**.

## Why, and why not a rewrite

OpenRocket is already free and works offline, so being offline is not what sets this app apart. What does is the
engineering depth, available without Claude:

- The server is about 32,000 lines of Java. About 22,000 of them (`or`, `report`, `calc`, `standards`, `units`) are
  analysis code that has nothing to do with AI. Claude is only one way of calling it.
- The 85 tools already have JSON inputs and outputs. A desktop interface can call the same tools Claude calls, so
  each feature is written once and works in both the app and chat.
- The interactive view (`show_design`, `src/main/resources/ui/workbench.html`, 2,566 lines) already has the 3-D
  rocket with cut-away and exploded views, the flight replay, linked plots, rule checks and editing by clicking a part.
- OpenRocket core does the physics, and files stay `.ork` in and `.ork` out. A team can switch between this app
  and OpenRocket at any time. That makes the app safe to try, and it means we never have to support every rare
  part on day one.

**We will not rewrite the physics.** We build a new front-end on the engine we have.

## Who it is for

Student and amateur teams designing a competition rocket (Launch Canada, IREC/Spaceport America Cup, Tripoli/NAR
level 1 to 3 projects): a chief engineer, recovery, structures and avionics sub-teams, and the people who review
their work.

## What it does that OpenRocket does not

| Area | OpenRocket 24.12 | This app (from tools that exist today) |
|---|---|---|
| Competition rules | none | rule checks against the competition's rule set and the team's standards, with a readiness dashboard (`check_requirements`, `design_status`) |
| Recovery | descent rate | parachute-size plots, opening shock, shear pins, ejection charges, bay fit, descent energy, deployment-delay sweep |
| Structures | none | fin flutter, fin FEA, structural loads, buckling, aerodynamic heating, material and thickness comparison |
| Supersonic and staging | weak drag model above Mach 1 | RASAero tables per stage; the sustainer's own margin after separation (`aero_analysis`, `import_aero_table`) |
| Avionics | none | sensor ranges, altimeter settings, power budget, radio link, simulated sensor CSV to test flight software |
| Uncertainty | basic | Monte Carlo landing ellipse and stability spread |
| Launch day | none | flight card, winds aloft; weather forecast when online |
| After the flight | none | altimeter log compared with the prediction (`compare_flight`), library of past designs and flights |
| History | undo | design diffs, milestones ("what changed since PDR"), progress log, reports |

Things no rocketry tool does yet, and the app's headline features:

1. **Live checks.** Every edit re-runs the simulation and the rules in the background. On Maple, after warm-up, an
   edit, a new simulation (0.16 s) and the full rule check (0.29 s) take under half a second. A failure shows the
   moment you cause it, next to the change that caused it.
2. **Requirements first.** The team enters its targets (apogee, rail exit speed, stability window, descent rates,
   the flutter safety factor). The app always shows the margin to each one, as a dashboard rather than a report you
   have to go and run.
3. **Design reviews built in.** Compare two versions side by side, see what changed since a milestone, and export
   the review package (report, flight card, drawings) in one click.
4. **Closing the loop.** Load the altimeter log after a flight; the app shows predicted against measured and keeps
   the record across years.

Where OpenRocket stays ahead at the start, and what we do about it:
- **Component editors:** it has a full editor for every part type, and those took years. Our first version edits
  the common parts. The `.ork` round trip covers the rest.
- **Plot configuration, fin templates, photo studio and localisation:** later, or never. These are not why teams
  would switch.

## Architecture

```
 ┌──────────────── desktop app (one installer, bundled Java runtime) ────────────────┐
 │                                                                                    │
 │   UI (web tech, in a window)  ──JSON-RPC tools/call over 127.0.0.1 + token──┐     │
 │                                                                             ▼     │
 │   engine: or / report / calc / standards / units   ◄──   tool registry (tools)    │
 │            │                                                                       │
 │            ▼                                                                       │
 │   OpenRocket core 24.12 (physics, .ork files, motors, parts database)             │
 └────────────────────────────────────────────────────────────────────────────────────┘
        the same engine and tools also serve Claude through the MCP server (stdio / HTTP)
```

- **One engine, two front-ends.** The app talks to the tool registry over local HTTP. `HttpTransport` already
  supports a random access token, which keeps other programs on the machine out. The MCP server stays as it is.
- **The UI calls tools the way the view does now.** `workbench.html` sends every call through one function
  (`callTool`, over `postMessage` to the chat host). The app version replaces that one function with a `fetch` to
  the local server.
- **Native file dialogs** come from the Java side (AWT `FileDialog`; `java.desktop` is already in our runtime). A
  browser page cannot see real file paths.
- **Packaging.** `./gradlew mcpb` already builds a trimmed Java runtime with `jlink`. The app adds `jpackage` for a
  Windows `.msi`, a macOS `.dmg` and a Linux `.deb`/AppImage, at roughly 80 to 120 MB each (240 to 300 MB once the
  window's Chromium went in). Every platform needs its
  own build, which CI can do on Windows, macOS and Linux runners.
- **Window.** First version: the app starts the engine and opens the UI in the system browser in app mode (Chrome
  or Edge `--app`, else a normal tab). No native code. Later: a webview shell (Tauri or wry) for a real window and
  menus. **Decide** at the end of Phase 2. JavaFX WebView is out: its WebGL support is too weak for the 3-D view.
  **Decided:** Chromium embedded in a Swing window through JCEF (jcefmaven), in the Java process, with the app's own
  menus. It is the same browser engine on every platform and needs no second toolchain (Tauri or wry would need
  Rust), at the cost of about 150 MB per installer. The browser window is gone.
- **Offline.** Everything is local except three features, which degrade cleanly:
  - Weather forecast: winds can always be typed in (`wind_profile`), and the last forecast is cached.
  - Product links (`add_from_link`): pasted page text works offline.
  - Motor database updates: the bundled database works offline; updates are optional.

### The one real engine change: numbers for the UI

Tool results are written for a language model: `"apogee": "3047 m (9997 ft)"`. A UI needs numbers, units and
structure. The engine already returns structured data in one place (`workbench_data` through
`CallContext.structured`).

Plan: each tool the app uses gets a structured result next to its text, with SI numbers, a dimension and labels.
The UI formats the numbers in the team's units. We do this tool by tool, in the order the screens need them. The
text output for Claude stays unchanged, and tests compare the two so they cannot drift apart.

### Repository layout (Phase 0)

A Gradle multi-project build:

| Module | Contents | Depends on |
|---|---|---|
| `engine` | `or`, `report`, `calc`, `standards`, `units`, plus the four classes they borrow from `mcp` | OpenRocket core, Gson, Batik |
| `server` | `mcp`, `tools`, `Main`, `Prompts`, `Manifest` (the MCP server, unchanged in behaviour) | `engine` |
| `app` | launcher, local API, file dialogs, packaging; `ui/` holds the front-end source | `server` (tool registry), `engine` |

What ties the engine to the MCP layer today is small:
- `ToolException`, imported 34 times: it becomes the engine's error type.
- `CallContext`, used in 7 places for progress, cancellation and image attachments: it becomes an engine progress
  context that the MCP layer and the app each fill in.
- `Log` and `Limits`, one use each.

Moving these is mechanical. The 397 tests and the 151-check benchmark must pass unchanged before and after.

## Screens

Each screen maps to tools that exist today. "New" marks work the engine does not do yet.

| Screen | What it shows | Tools behind it |
|---|---|---|
| Home | recent designs, readiness of each, open/new/import | `open_design`, `design_status`, `design_library` |
| Design | component tree, 3-D model, property panel, mass budget, CG/CP | `get_design`, `edit_components`, `add_component`, `remove_component`, `apply_preset`, `search_parts`, `mass_budget`, `workbench_data`, `undo`/`redo`, `history` |
| Requirements | targets and live margins, rule results | `check_requirements`, `get_standards`, `update_standards`, new: target margins |
| Flight | simulation, plots, flight replay, events | `run_simulation`, `get_flight_data`, `flight_configuration`, `animate_flight` |
| Propulsion | motor search and ranking, custom motors, staging | `search_motors`, `rank_motors`, `set_motor`, `create_custom_motor`, `import_motor_file`, `set_stage_separation` |
| Aero | stability across Mach numbers, per stage, RASAero import | `aero_analysis`, `import_aero_table`, `roll_analysis`, `compare_shapes` |
| Recovery | chute sizing plots, deployment, shock, pins, charges, bay fit | `parachute_sizes`, `size_parachute`, `set_deployment`, `recovery_analysis`, `opening_shock`, `shear_pins`, `ejection_charge`, `recovery_bay_fit`, `descent_energy`, `deployment_delay_sweep` |
| Structures | flutter, FEA, loads, heating, materials | `fin_flutter`, `fin_fea`, `structural_loads`, `aero_heating`, `compare_materials`, `pressure_vessel` |
| Avionics | sensors, altimeters, power, radio, sensor CSV | `sensor_check`, `altimeter_settings`, `power_budget`, `radio_link`, `sensor_data`, `add_avionics_bay` |
| Studies | sweeps, optimiser, Monte Carlo | `sweep`, `optimize`, `optimize_fins`, `ballast`, `monte_carlo` |
| Launch day | winds, flight card, rail buttons | `weather_forecast`, `wind_profile`, `flight_card`, `rail_buttons` |
| Review | compare versions, milestones, progress, reports, exports | `compare_designs`, `progress_log`, `generate_report`, `export_geometry`, `export_flight_data`, `render_3d`, `draw_rocket` |
| Flight data | load a log, predicted against measured | `compare_flight`, `design_library` |

## Phases

The durations assume 3 to 4 students at about 8 hours a week each. Each phase ends with a demo, not a date.

### Phase 0: groundwork (about 2 weeks)
- Split the build into `engine`, `server` and `app`; tests and benchmark unchanged.
- Front-end stack (decided): Svelte and TypeScript with Vite, and three.js for 3-D (the current
  view already uses WebGL), with the built front-end shipped in the installation's `ui` folder.
- A "hello rocket" app: the installer starts the engine, opens a window, loads Maple and shows the 3-D model.
- CI builds the installers for Windows, macOS and Linux.

Done when: a teammate who has never seen the code installs it on a fresh laptop with no internet and sees Maple.

### Phase 1: first usable version (6 to 8 weeks)
- Home, Design (property panel and editing by clicking the 3-D model; the tree read-only), Flight, Requirements
  with live checks, and the main Recovery and Structures results (chute sizing, flutter).
- Open, save and undo; `.ork` files open unchanged in OpenRocket afterwards.
- Structured results for the tools these screens use.

Done when: the team does one real design review of the competition rocket in the app instead of OpenRocket plus
spreadsheets.

### Phase 2: full engineering suite
- Full component tree editing: add, remove, reorder, catalog parts.
- Propulsion and staging, Aero with RASAero import, all of Recovery, Structures, Avionics and Studies.
- **Decide** the window shell (browser app mode or a webview). Decided: Chromium through JCEF (see Window above).

Done when: the team stops opening OpenRocket for day-to-day work.

### Phase 3: launch and after
- Launch day: offline flight card, cached forecast, rail buttons.
- Flight data: load the altimeter log, predicted against measured, the design library across years.
- Review: side-by-side comparison, milestones, the one-click review package.
- Optional assistant: chat with the design using the team's own API key, through the same tools. Off by default.

### Phase 4: other teams
- Beta with two or three other teams; a bug tracker and release notes.
- A validation page: predicted against measured apogee for every flight we can get, published with each release.
- Docs: a getting-started guide and one worked example per screen.

## New functions: order of work

Easiest first. "Easy" means the engine already does the work and the app needs only inputs and a results view.

**Tier 1: the engine's analyses in the app (done).** An Analysis page lists the engine's tools by area. Each
one gets a form built from the tool's own input definition: the design, simulation, configuration and the right
parts are filled in or picked from lists, launch-condition overrides are folded away, and paths default to the
designs folder. Results show as tables, with pass/warn/fail colours and the tool's plots. This covers:
- Checks: rule check, readiness.
- Stability: stability and drag vs Mach, ballast, roll from fin cant.
- Recovery: recovery loads, parachute size chart, sizing for a descent rate, deployment delay, shear pins, ejection
  charge, opening shock, recovery sections.
- Structures: fin flutter, airframe loads, heating, pressure vessel.
- Launch and avionics: rail buttons, flight card, altimeter settings, radio link, sensor ranges.
- Studies: Monte Carlo, motor ranking, parameter sweep, nose and fin shape trade, material trade.
- Files: design review report, flight data CSV, geometry export, mass budget template.

**Tier 2: dedicated screens where a form and a table aren't enough (done).**
- Rule check on the Design page, run again a moment after each edit (about 0.6 s), counts in the panel title.
- Plots: stability and Cd vs Mach, parameter sweep, late deployment; the engine's own Monte Carlo landing map and
  flight log comparison.
- Forms for the tools that take lists of items, as small editable tables: recovery bay fit, landing energy, the
  optimizer's variables, power budget circuits, sensors, winds aloft. The engine now describes every list item's
  fields (bay fit, landing energy and wind levels only described them in words), which also helps Claude.
- Compare with another open design or a file, the progress log, the edit history.
- RASAero table import per stage, and a flight log against the simulation, with a file browser.

**Tier 3: new work in the engine or the app (done).**
- Requirements first: a Targets page with the team's limits (min, goal, max) on 16 flight numbers and the live margin
  to each, next to the competition rules. The engine's new `targets` tool does the work, so Claude checks the same
  targets; they live in the team standards file.
- Flight data: an altimeter log flown against the simulation in the day's conditions (both curves, the numbers, the
  fitted drag factor), and the library of past rockets with predicted against measured apogee across the years.
- Editors: freeform fin points (drag, add, remove, type), convert trapezoidal and elliptical fins to freeform, the
  parts catalogue for every part that has one, paint colour and shine.
- Installers with Java included (`./gradlew rocketmaxxingInstaller`: .msi, .dmg, .deb, or an app folder), built for all
  three platforms by a workflow.
- The optional assistant, through the same tools, off by default: with the team's own Claude subscription (Claude Code),
  ChatGPT subscription (Codex) or an Anthropic API key.

**Ease of use (done).**
- Finding things: search (Ctrl+K) over analyses, pages, actions, parts and settings; a Guide (first session, pages,
  which analysis answers which question, the team standards); Analysis › Start here; a guided tour of the Design page;
  ⓘ help on every analysis and input, with examples in the team's units and the needed inputs marked.
- Right-click menus on every page, with the commands for what is under the pointer.
- Parts: drag in the list to move a part into another, slide a part along the rocket with snapping to its neighbours,
  line up at the front, middle or back of its parent, copy and paste, arrow keys and shortcuts.
- The view: an exploded mode, studio lighting and a floor shadow, part names on hover, smooth zoom, clearer selection.
- Errors labelled by whose problem they are (input, output, system, physics), with a problems list and a bug report.

## Validation

Teams trust a tool for its track record, not its features. From Phase 1 on:
- A flight-data folder in the repo (with each team's permission). Every release reports predicted against measured
  apogee, maximum speed and descent rates.
- The existing physics tests and benchmark run on every change.
- Agreement checks against RASAero for supersonic cases, and against hand calculations for recovery and structures.
- Started: [WORKED_EXAMPLE.md](WORKED_EXAMPLE.md), the Notre Dame Rocket Team's 2020 L1395 flight against its
  altimeter and accelerometer log (stock OpenRocket 9% high on apogee; the motor, not drag, was the cause).

## Team and process

- **Roles:** an engine lead (Java, tests), a UI lead (front-end, 3-D), a validation lead (flight data, comparisons)
  and a product owner from the rocket team, who decides what each phase includes.
- **Handover:** students graduate. Keep module boundaries clean, every feature tested, a short design note per
  screen, and two people who know each part.
- **Licence:** GPL v3 or later, the same as OpenRocket and this repository. The app is free and open source; anyone
  who distributes a modified version must publish its source.

## Risks

| Risk | Mitigation |
|---|---|
| The app takes time from the competition rocket | The rocket comes first. Phases end in demos, not dates. Pause during the build season and before launch. |
| The component editor balloons | Edit common parts first; `.ork` round trip for the rest. |
| Tool output is text, not data | Structured results tool by tool, tested against the text. |
| Installers on three platforms | CI on all three from Phase 0, and a fresh-laptop test each release. |
| Wrong numbers damage trust | The validation page, agreement checks, and labelling what is simulated, calculated or assumed. |
| Maintainers graduate | Two people per area; docs and tests as part of "done". |

## Open decisions

1. **Name (decided):** RocketMaxxing; it does not suggest it is the OpenRocket project.
2. **Front-end stack (decided):** Svelte 5 with three.js.
3. **Platforms first:** whichever the team uses most, probably Windows, then macOS.
4. **Who builds it, and when**, relative to the Launch Canada 2027 build schedule.
5. **The assistant (decided):** optional and off by default; it connects through the team's own Claude Code (Claude
   plan), Codex (ChatGPT plan) or an Anthropic API key.
