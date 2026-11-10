# OpenRocket MCP — design and roadmap

## Goal

Let a student competition team state **goals** and have Claude reach them with real physics:

- "Land under 20 ft/s" → which parachute, which shock cord, will it fit, what loads.
- "Find the right motor" → search + simulate candidates against apogee and rule constraints.
- "Optimize CP/CG" → keep 1.5–2 cal through the whole ascent (and for the sustainer after staging) at minimum cost in
  apogee.
- Any vehicle: single or multi-stage, commercial, research or **custom (liquid / hybrid)** engines.

## Principles

1. **The server does the numbers, Claude does the intent.** Iterative numeric work (sizing, ranking, sweeps, delay
   studies) runs server-side in one tool call. Claude turns goals into constraints, weighs trade-offs and explains.
2. **Simulation inputs over hand estimates.** Deployment airspeed, density and mass come from the OpenRocket simulation.
3. **Team standards are data.** Safety factors, pin ratings, packing factors, Cx and the launch site live in a committed
   JSON file so the whole team gets reproducible answers.
4. **Rules are data.** Competition thresholds live in a rule-set JSON with section references (Launch Canada DTEG R4
   built in), so the next revision is a data change.
5. **Honest outputs.** Every number is simulated, calculated or assumed, with the method stated; nothing replaces
   ground tests.
6. **Standalone on published OpenRocket.** Depends on `info.openrocket:core` from Maven Central instead of forking
   OpenRocket, so upgrading OpenRocket is a version bump.

## Architecture

```
Claude / Codex  ──stdio or HTTP JSON-RPC──▶  McpServer (tools, prompts, resources)
                                                 │
                         ┌───────────────────────┼─────────────────────────┐
                    tools/*Tools            or/* (OpenRocket)          calc/* (pure math)
                  (argument parsing,     Designs, Components,       Atmosphere, Parachutes,
                   units, rendering)     Analysis, Sims, Motors,    OpeningShock, Charges,
                                         Recovery, Requirements     Packing
                                                 │
                                   info.openrocket:core 24.12 (headless)
```

- `report/` — Markdown report, flight card, SVG plots and drawings, PNG for the chat, KML; the software 3-D renderer
  (`Raster3d`), the part model (`Model3d`), exploded / cut-away views (`View3d`) and the flight animation
  (`FlightAnimation`, written by `Video` as GIF and, through ffmpeg, MP4).
- `units/` — parsing ("20 ft/s") and display in metric / imperial / both.
- `standards/` — team standards merged over defaults + rule set.
- Designs are held in memory; `save_design` writes .ork files usable in the OpenRocket app.
- Custom engines are written as RockSim `.rse` files (per-point mass and CG) and loaded through OpenRocket's own loader.

## Status

### Phase 1 — done (v0.1.0)

- Design open/create/save, component tree, reflection-based property editing, add/remove components, deployment and
  stage-separation events, flight configurations.
- Static stability per stage stack (full vehicle, then each stack after a separation).
- Simulation summary per branch (stage): rail exit, TWR, stability min/max during ascent, air-start tilt/altitude,
  deployments, descent rates, landing distance; down-sampled flight data; parameter sweeps (conditions or any property).
- Motors: search (incl. certification level), simulated ranking, import .eng/.rse, custom liquid/hybrid engines with
  tank CG shift.
- Recovery chain: deployment conditions from the sim, opening load (Knacke Cx and finite-mass inflation, floored at
  steady drag), shear pins, deployment-delay sweep against pin capacity, parachute sizing with real presets, bay volume
  fit, black powder charges (primary/backup, charge-well limit), landing energy per section.
- Launch Canada DTEG R4 requirement check, including the 30 km/h wind case and staged-flight rules.
- Claude Code (`.mcp.json`, skill) and Claude Desktop setup; CI on Windows/macOS/Linux.

### Phase 2 — done (v0.2.0)

- **Performance**: array-backed, cached flight-data access (OpenRocket's `DataBranch.get()` copies a whole column per
  call, which made per-point lookups quadratic); what-if simulations run on rocket copies in a thread pool
  (~3x faster on 4 cores) and never touch the open design.
- **Repeatability**: OpenRocket seeds wind turbulence from `new Random()` when simulation options are created and
  `setRandomSeed()` does not reach the wind model; variants are re-seeded explicitly (common random numbers for
  comparisons, per-run seeds for Monte Carlo).
- **optimize**: parallel goal-seeking over 1-3 component properties (bracketing grid in 1D, shrinking Latin
  hypercube in 2-3D) with stability / rail-exit / Mach / apogee constraints; reports the current design, the best
  point and alternatives; optional apply.
- **monte_carlo**: landing ellipses per stage, apogee spread, worst ascent stability and rail exit with rule
  violation counts, worst deployment airspeed and opening load per device.
- **Reports**: `generate_report` (Markdown + stability-vs-time SVG plots for DTEG R10.3.2, with on-rail stability
  filled in from Barrowman CP and simulated CG) and `export_flight_data` (CSV).
- **Bay volume from the design**: interior volume of body tubes, nose cones and transitions.

### Phase 2.5 — dogfooding fixes (v0.3.0)

Found by building a 10k ft Launch Canada rocket from scratch through the tools, then encoded in
`scripts/benchmark.py` (30 checks, run in CI):

- Safety: recovery devices deploying before apogee (OpenRocket's default "motor ejection charge" event) or more than
  3 s after it are flagged in check_requirements, rank_motors and recovery_analysis; supersonic flight warns about fin
  flutter and aerodynamic accuracy.
- rank_motors: works on a new design, filters motors that do not fit the mount length, removes duplicates, reports
  ascent stability with each motor's mass, ranks rule-compliant motors first, and samples twice (spread, then around
  the target impulse).
- optimize: constraints checked at the rule set's maximum wind as well; infeasible results name the binding limits;
  a repair line-search crosses thin feasible bands.
- Consistent turbulence: every run derives its gusts from the simulation seed, so check_requirements, optimize and
  sweeps agree.
- Recovery: shear pins only for bays that must stay closed during an earlier event; harness working load for all.
- Stability failures report the angle of attack and the zero-AoA margin at the minimum.
- Editing: atomic add_component, materials by name, student-style aliases (span, sweepLength, mass), sweep is a
  length.
- Output size: compact JSON and a one-line-per-component tree (-33% tool output).

### Launch Canada 2027 edicts (v0.4.0)

From the "LC 2027 DTEG and R&R Edicts" (to become DTEG R5), rule set `launch-canada-2027` (now the default):

- Length-to-diameter ratio (shall ≤ 45, should ≤ 25).
- Damping ratio along the ascent (shall 0.03–0.5, should 0.05–0.3): Barrowman/Apogee formulation with OpenRocket's
  per-component CNα and CP (verified to reproduce OpenRocket's vehicle CP), simulated inertia and jet damping; the
  vehicle configuration follows staging.
- Static margin ≥ 10% of body length from rail exit to twice each stage's burn time (the stack after separation uses
  its own length); `optimize` with `meetRules` derives the floor max(1.5 cal, 10% x L:D).
- Metal rail buttons prohibited; SRAD/hybrid/liquid static-fire Isp ≥ 100 s; concentric avionics reminder above
  Mach 0.7; pop-test list for the dress rehearsal; manual checklist for electronics, radio allocations, structures,
  SRAD test sequence and operations.
- `pressure_vessel` and `advanced_probation` tools (GLPP probation levels, AASI).

### Structures, ballast, vehicle dispersion (v0.5.0)

- `fin_flutter`: NACA TN 4197 flutter speed per fin set at every point of the simulated flight (local pressure and speed
  of sound), with the corrected constant (Peak of Flight #615); booster fins until separation; thickness / shear
  modulus to reach the team margin. Fin-material shear moduli and the required margin are team standards
  (`structures.*`). Also a check_requirements item (team standard, not a rule).
- `ballast`: nose weight for a minimum simulated ascent stability (default: the rule-set floor), analytic first guess
  corrected by the gap between static and simulated margin, then secant iterations on simulations; reports the apogee
  and rail-exit cost.
- `monte_carlo` part 2: structure mass (per-component scaling, motors excluded), airframe drag and motor thrust
  (simulation listeners), per-parachute Cd; `drivers` = correlation of each randomized input with apogee, minimum
  stability and landing distance.
- Weighed-mass overrides: warnings from edit_components, add_component, sweep and optimize when a section's
  subcomponent mass/CG override hides the change; ballast raises the override.
- Report: wind-sensitivity table (0 to the rule-set maximum wind) for the flight card.
- Tests: 31 -> ~170 (protocol, standards merging, SVG well-formedness, physics property tests, requirements verdicts,
  Monte Carlo determinism and listeners, flutter and ballast against hand calculations). Bugs found by the new tests:
  saved standards dropped null keys; ballast returned 0 kg when only the simulated minimum was short; team material
  keys lost to overlapping default keys.

### Deeper OpenRocket (v0.6.0)

- `aero_analysis`: BarrowmanCalculator queried directly — total CD split into friction / pressure / base, CP, CNalpha,
  launch and burnout margins vs Mach, per-component drag at one Mach, OpenRocket's geometry warnings. Also a table in
  the report.
- `wind_profile`: OpenRocket's multi-level wind model (levels from a forecast or sounding, or a power-law shear profile);
  per-level turbulence seeded for repeatability; wind overrides scale / rotate the profile.
- `search_parts` / `apply_preset`: the full parts database (all ComponentPreset types), filters on diameter, maker,
  text and material; presets load dimensions, material and mass.
- `draw_rocket`: cut-away from OpenRocket geometry (body radius profiles, fin outlines, pods) with the internal
  components drawn by kind (typed mass components, recovery devices, shock cords, rings, couplers, shoulders, motors at
  their overhang), separation points from the recovery sections, labels without overlap, legend, CG / CP; in the report
  as rocket.svg.
- OpenRocket 24.12 quirks found and handled: `SimulationOptions.getWindSpeedAverage()` (and the direction, turbulence
  and deviation getters) select the average wind model, so merely reading the wind discarded a profile; the launch CG
  from `MassCalculator` changes over the first calls after loading (lazy position resolution) — designs are settled on
  open and before analyses / simulations.

### External data (v0.7.0)

- `import_aero_table`: RASAero II aero export (lowest-alpha rows, CD power-off / power-on, CP in inches) or plain
  Mach / CD [/ CP] CSV. A simulation listener replaces OpenRocket's axial CD (power state from thrust) until the first
  separation; the CP gives a stability item in check_requirements. Validated by importing a table generated from
  OpenRocket's own CD (apogee within 3%).
- `compare_flight`: altimeter CSV (header or explicit units, pad altitude removed, launch alignment), apogee / time to
  apogee / drogue and main descent-rate comparison, overlay SVG, and the airframe drag factor that reproduces the
  measured apogee (parallel sims over 0.5-2x). Validated closed-loop: a flight flown with 1.3x drag is recovered as 1.3.

### Design studies (v0.8.0)

- `compare_shapes`: nine nose profiles (conical, tangent ogive, elliptical, 1/2 and 3/4 power, parabolic, 1/2 parabola,
  Von Karman, LV-Haack), optional nose lengths, and fin edge profiles flown in parallel on copies; CD at one design Mach
  for all; best feasible option and best nose + best fins combined; explicit "none" when nothing meets the stability
  floor.
- `recovery_sections`: sections split at the forward end of every bay (or named joints); masses from OpenRocket's own
  per-component mass analysis (weighed-section overrides scaled) plus burnt-out motor cases - they add up to the
  simulated landing mass of each stage; landing energy vs `recovery.maxLandingEnergy`, drogue-only contingency, bay fill
  from packed dimensions, black powder estimate.
- `structural_loads`: N(x) = m_fwd/m (T - D) + D_fwd over boost and coast; bending at max q with a gust and at the
  largest simulated q·sin(AoA), with inertial relief (rigid body); per stack phase (full vehicle, then each stack after
  separation); thin-wall stress, required allowable with `structures.loadSafetyFactor`, optional margin. Verified by
  equilibrium: the bending moment vanishes past the tail and the axial force there equals thrust.

### Launch day, heating, roll (v0.9.0)

- `weather_forecast`: Open-Meteo hourly forecast by coordinates (asks for them when the standards do not have them):
  10 m wind, gusts, 2 m temperature, surface pressure, 80/120/180 m winds and 16 pressure levels 1000-100 hPa with
  geopotential heights (levels below ground or inside the near-surface band dropped); applied as an AGL wind profile
  plus site altitude, temperature and pressure; `forecastJson` fallback when the server is offline. Tested against a
  local HTTP server serving a recorded-format response.
- `flight_card`: one-page Markdown card from the simulation in the day's conditions.
- `aero_heating`: stagnation / recovery temperature and Sutton-Graves nose-tip flux along the flight vs
  `structures.maxServiceTemperature`.
- `roll_analysis`: cant sweep on copies; roll rate vs pitch natural frequency sqrt(C1 / I) / 2 pi (roll resonance).

### Design diff and team access (v0.10.0)

- `compare_designs`: baseline = open design, .ork file or git revision (`git show REV:./file`, revision names
  validated, no option injection). Both flown in the reviewed design's conditions (options copied, multi-level wind
  profile copied with its altitude reference). Components matched by persistent id, else type + name; property
  changes from the same reflection `describe_component` uses. Rule checks compared by item.
- Team server: `HttpTransport` (JDK `HttpServer`), token by header or URL path (constant-time compare), Origin check,
  per-design `ReentrantLock` guard in `McpServer`, `Context.path` sandbox (normalised prefix + real-path check for
  symbolic links), rule-set files limited to the workspace, workspace-relative output filter, files page.
- `.mcpb` extension with a `jlink` runtime; manifest generated from the tool registry and validated with the `mcpb`
  CLI; extension smoke test in CI on four platforms.
- RASAero / aero tables saved beside the design (`NAME.aero.json`) and restored on open.

### Examples and plots (v0.11.0)

- `docs/EXAMPLES.md` generated by `scripts/make_examples.py` from real runs (one 10,000 ft rocket from design to
  launch day), rebuilt in CI.
- Landing map (`monte_carlo plotPath`) and flight profile (`run_simulation plotPath`, report).
- Optimizer: fin flutter margin per point, `minFlutterMargin` constraint, on with `meetRules`.
- Fixes found by the examples: `set_deployment` / `set_stage_separation` with `configuration: all` before any motor
  (no configurations yet) now set the default for configurations created later; motors that list no ejection delay
  get a plugged delay instead of NaN, which made every simulation fail; design diff ignores override values that are
  switched off.

### Avionics and cut-away drawing (v0.12.0)

- `add_avionics_bay`: builds the bay from OpenRocket's typed mass components (ALTIMETER, BATTERY, TRACKER,
  DEPLOYMENTCHARGE, RECOVERYHARDWARE) per the 2027 electronics edicts; packs recovery against it and checks the free
  length to the nose shoulder / motor mount or motor.
- `check_requirements`: redundant deployment electronics and one power supply per altimeter and tracker.
- New parachutes get a packed size from the canopy diameter (about 0.025 in³ per in² of D²) so drawings and bay checks
  are realistic; the unit parser accepts its own "274.3 mm (10.8 in)" output.

### Fin design, FEA and CFD hand-offs (v0.13.0)

- `optimize_fins`: the optimizer takes a custom applier (shape space of buildable trapezoids) and flies candidates on
  OpenRocket's drag even with an imported table; rule-derived constraints shared with `optimize`.
- `fin_fea`: CalculiX deck (S8R, engineering constants, clamped root, flight pressure, *FREQUENCY), run when ccx is
  found ($CCX or the PATH; never an executable path from a tool argument), results read from .dat / .frd; CI installs
  CalculiX on Linux and checks the model against cantilever plate theory.
- `export_geometry`: multi-solid ASCII STL (closed and outward-oriented, checked by edge pairing and divergence-theorem
  volume), DXF R12 fin patterns, CFD run matrix and results template.
- `structural_loads csvPath`; standards gain youngsModulus, poissonRatio and strength tables.
- Fixes: `aero_analysis` drag breakdown counted one fin of a set (OpenRocket's per-component drag is per copy), and
  `structural_loads` split drag along the body the same way; unit inputs accept fractions ("1/8 in").

### Working with Claude (v0.14.0)

- MCP: per-call context (progress notifications from `Variants.runAll`, cancellation checkpoints, image content);
  stdio requests run one at a time in order on a worker thread, while the reader answers `ping` and cancellation at
  once; Streamable HTTP answers a tool call with a progress token as
  server-sent events.
- Images: our SVGs rasterized with Apache Batik (`report.Png`), best effort (no image, same text, if it fails).
- `design_status`; `undo` / `redo` / `history` (rocket copies around every editing tool, kept when OpenRocket's
  modification id changes); KML landing zones from `monte_carlo`.

### Mass tracking (v0.15.0)

- `mass_budget` (`or.MassBudget`): flexible CSV parsing, component matching (id, exact name, unique partial, word
  overlap; duplicate names reported as ambiguous), comparison and totals, apply as OpenRocket overrides.
- Fix: per-component masses with a weighed section (mass override of a component and its subcomponents) counted the
  section twice (OpenRocket keeps the override on the section's own entry); now spread over the section in proportion
  to the parts' own masses, so they add up to OpenRocket's structure mass. Affects `structural_loads` too.

### Review and cleanup (v0.15.1)

- Fixes:
  - stdio: `ping` and cancellation are recognised by parsing the request, not by searching its text;
  - simulations that were never flown no longer stay in static maps (weak maps), and closing a design frees its
    aero table unless another open copy of the same rocket uses it;
  - `mass_budget`: several lines for one part are summed (CG mass-weighted), and a part the design lacks is added when
    the line names a parent;
  - enum and unit names parse the same in every system locale (`Locale.ROOT`, e.g. Turkish);
  - OpenRocket's deprecated `getActiveComponents()` replaced.
- Cleanup: one `Xml.esc` for SVG / KML / HTML text; imports instead of fully-qualified names; `serialVersionUID`s.
- Docs: the tool reference split into one section per area, with one bullet per tool.

### 3-D views and flight animation (v0.16.0)

- `render_3d` (`report.View3d`, `report.Model3d`, `report.Raster3d`): exploded, cut-away and assembled views from
  OpenRocket's geometry, with numbered balloons and a parts list with masses. Rendering is pure Java (depth buffer,
  Gouraud shading with a crease angle, 2x2 supersampling), so it runs headless in the desktop extension.
- `animate_flight` (`or.FlightTrack`, `report.FlightAnimation`, `report.Video`, `report.Gif`): position, attitude
  (orientation theta / phi, checked against the flight path in the tests), accumulated roll, events and HUD numbers from
  the simulation. A time warp is real time in the burn and slowed around apogee and deployments. Frames are
  deterministic, so they render in parallel and stream to a GIF (one median-cut palette) and to ffmpeg for the MP4. A
  key-moment stills sheet is attached in the chat.
- Review fixes before release:
  - dropped stages (OpenRocket starts their branch at liftoff) come off at their separation event and are drawn on the
    vehicle until then;
  - flames burn at every nozzle of the lowest attached stage and of attached side boosters;
  - the pad height allows for the booster;
  - smoke thins out instead of filling the frame;
  - apogee without an event is the highest point;
  - motors are placed by their own mount;
  - vertex normals are shared safely between render threads;
  - parallel frames are capped by free memory, and very large stills skip supersampling;
  - a cancelled render stops ffmpeg and removes the partial files.

### Two-stage rockets in the 3-D views and animation (v0.16.1)

- `FlightTrack`: events name their stage when there are several (booster burnout / separation, sustainer ignition /
  burnout). Dropped stages' deployments and landings are events of their own track (`Event.branch`).
- `FlightAnimation`:
  - a dropped stage pivots on its own simulated CG and hangs under its own chute;
  - a booster camera inset follows it from separation to touchdown;
  - the chase camera pulls back at staging;
  - smoke is drawn only while a motor burns (no trail through a coast between stages);
  - the trajectory inset and flight summary include every stage;
  - key-moment stills are distinct instants chosen by priority.
- `View3d`: extra gap between stages in exploded views, stage brackets with names and masses, and `stage` in the parts
  list.

### GIF colours (v0.16.2)

- Fix: animation GIFs showed wrong, flashing colours. The JDK's GIF writer stored its own default 216-colour table and
  remapped the indexed frames, so the palette in the file did not match the pixels. `report.Gif` now writes the file
  itself: GIF89a with our median-cut palette as the global colour table, and LZW-coded frames. A test decodes the GIF
  and compares its colours with the rendered frames. The PNG stills and the MP4 were never affected.

### Bug sweep and older designs (v0.16.3)

- A sweep ran every tool that needs no extra input on every bundled example through the real server. Fixes:
  - designs with catalogue parts (the "Deployable payload" example) failed to open: OpenRocket's core module does not
    bind the `ComponentPresetDao` its loader asks for, so `OrRuntime` binds it;
  - `add_avionics_bay` and `edit_components` could not use materials carried in the design's file (e.g. a
    manufacturer's kraft paper): material names now also match materials used in the design, and the bay copies the
    tube's material object;
  - `render_3d` on an empty design gave an internal error instead of saying there is nothing to draw, and stability
    showed "NaN cal".
- Older designs: RockSim `.rkt` files open as imports, never written back over the original. OpenRocket's conversion
  warnings are returned by `open_design`. Four more bundled examples are listed (3-D printed fins, base drag, two
  pod designs).
- Team standards: stiffness, strength and Poisson's ratio for balsa, basswood (*Wood Handbook*) and printed PLA, PETG
  and ABS, so flutter and fin FEA run on common student fin materials.
- `OrRuntime.motors()` no longer uses a deprecated OpenRocket call.

### Design library and rail buttons (v0.17.0)

- `design_library` (`or.Library`): scans a folder (recursively, up to 2000 design files) for .ork and .rkt designs and
  .csv altimeter logs. Designs are read with OpenRocket's loader without being registered, summarised, and cached by
  path, size and modification time.
  - The year comes from the last 19xx/20xx in the relative path, else the file date.
  - The predicted apogee comes from the saved results of the rocket's selected configuration, else a copy of its first
    simulation is flown (`simulate`, default true).
  - A log belongs to the design whose file name appears in the log's name. The rocket name comes second, since several
    files often share one. Ties go to the nearest folder. Without a name match, a log goes to the only design in its
    folder. CSVs that are not a climb above 10 m are listed as skipped.
  - Search by text, diameter (2% slack), impulse class or range, years, stages and "flew". `similarTo` ranks by a
    log-ratio distance on diameter (weighted 2x), launch mass and total impulse.
  - The trend gives the prediction error per flight, the mean by year, the overall bias and scatter, and a first-guess
    scale factor when the bias exceeds 5% over two or more flights.
- `rail_buttons` (`or.RailButtons`): OpenRocket 24.12 shortens the rail for launch lugs (effective launch rod length)
  but not for rail buttons, so its rail exit velocity counts rail the rocket never uses.
  - The tool runs the simulation and maps speed and time against travel along the rail (altitude / cos(launch angle)).
    It evaluates each layout:
    - guided travel = rail - standoff - (aft end - aft button);
    - the single-button time between the two buttons clearing the rail top;
    - tip-off rate and angle of a rigid rocket pivoting on the aft button under m g sin(theta) at the CG and
      q S CNα atan(w/v) at the CP;
    - slop = clearance / spacing;
    - button side loads as a couple.
  - Aft button: the aft end of the last main-stack body tube minus an edge margin (max(15 mm, 0.1 D)), over a backing
    ring within a calibre when there is one. An existing lower button is kept.
  - Forward button: 5 mm steps over the main-stack body tubes, minimising tip-off angle + slop. It snaps to a
    ring, bulkhead or coupler within half a calibre if that costs at most 10% more.
  - `apply` reuses the design's buttons: one component with two instances on a shared tube, else one per tube. With
    no buttons, it adds a Delrin pair. The result is verified against OpenRocket's instance positions, and undo
    reverts it.

### Bug and speed sweep (v0.17.1)

- A sweep ran about 45 tool calls on each of the 15 bundled examples, plus an edge-case probe (zero, negative, unknown
  and oversized inputs). Fixes:
  - **Arguments:** `McpServer` refuses arguments a tool does not declare, naming the tool's arguments and the closest
    match. Before, they were dropped silently: `search_motors` with a made-up filter returned every motor. Enum
    arguments are checked and normalised (case, spaces, hyphens). An unknown value used to surface as a Java
    `IllegalArgumentException` or a bare "Unknown action".
  - **Physical inputs:** `Args.positive` guards masses, rates, lengths and volumes that must be above zero
    (`size_parachute` returned an "Infinity" parachute for 0 ft/s; `ejection_charge` a 0 g charge for a 0-length
    bay). Budget lines with negative mass are refused. Negative `limit`, `maxCandidates` and `index` values no
    longer throw index errors.
  - **`sweep`:** an unknown launch-condition name is refused (it used to fly the same rocket N times).
  - **`rail_buttons`:** a standoff taller than the rail is refused with that reason.
  - **Component references** may be a piece of a name that only one part has. Ambiguous pieces list the candidates.
  - **`optimize_fins`** prefers the main airframe's fin set over fin sets on pods or side boosters, and names the
    candidates when it cannot choose.
  - **User files** (`TextFiles`): UTF-8 or Windows-1252, without a byte-order mark. Logs, budgets and aero tables
    exported from Windows software failed to read before.
  - **`design_library`:**
    - years are no longer taken from motor names ("M2020");
    - simulation exports are not counted as flights;
    - CSVs over 50 MB are skipped;
    - linked files are not followed;
    - designs are read in parallel on the simulation pool.
- Speed: `AscentOnly` ends a flight branch 0.5 s after its first recovery deployment past apogee (or 60 s after
  apogee). Studies that read only the climb use it: motor ranking, optimizers, shape study, ballast and roll. About
  half the integration steps are skipped. Ascent results are identical (`AscentOnlyTest`), and the benchmark went
  from 73 s to 64 s. Monte Carlo, `compare_flight` and anything reporting descent or landing still fly the whole
  flight.
- Repeatability: OpenRocket does not save a simulation's random seed in the .ork. Each open drew a new turbulence
  seed, so tilt, drift and landing points changed from session to session (the two-stage example's sustainer
  landing ranged over 32-44 m across three runs). `Designs.repeatable` gives every opened simulation the fixed seed
  that new simulations use, without marking the file changed; `RepeatableTest` reopens a file and compares.
- Server instructions mention `rail_buttons` and `design_library`.

### Electronics (v0.18.0)

- `or.SensorSim`: the flight resampled at a fixed rate into what each sensor measures.
  - **Specific force on x:** pad support up to liftoff; on the rail, thrust minus drag with the rail carrying g sin(angle)
    sideways. In flight, (T - D) / m.
  - **Specific force on y:** CN q A / m from OpenRocket's normal-force coefficient (zero after the first deployment).
  - **Gyro and air:** OpenRocket's roll, pitch and yaw rates; ambient pressure and temperature.
  - **GPS:** latitude and longitude (OpenRocket keeps them in degrees). No fix above 515 m/s or 18 km.
  - **Phases:** pad, rail, boost, coast, descent, landed.
  - **Measurement:** Gaussian noise, rounding to the resolution, clipping at the range (and at a barometer's min/max).
  - **Output:** the CSV, an events CSV, and `check`. The check splits accelerometer peaks into boost/coast and parachute
    openings (an upper bound, since OpenRocket opens canopies instantly), and finds the window above
    `baroUnreliableAboveMach`.
- `or.AltimeterSettings`:
  - **Channels:** per recovery device from its deployment configuration. Apogee devices get primary apogee + delay and
    backup + `backupDrogueDelay`; altitude devices get primary h and backup h - `backupMainOffset`. Ejection deployment
    gets a warning.
  - **Matching:** each channel is matched to its simulated opening by component id; events refer to the simulated copy.
  - **Mach lockout:** ceil(last time above the Mach limit before apogee + margin), with a warning when it comes within
    2 s of apogee.
  - **Static ports:** via `Avionics.staticPorts`, shared with `add_avionics_bay`.
  - **Card:** Markdown with a checklist.
- Standards: `electronics.sensors` (typical student parts) and `electronics.altimeter` (backup delay and offset, Mach
  limit, lockout margin).
- Tests: `SensorSimTest`, which checks the accelerometer against the trajectory and covers 1 g on the pad and under the
  canopy, noise sd, clipping, the CSV and events file, range failures and the transonic window; and
  `AltimeterSettingsTest`, which covers lockout, backups, static ports, the card, and two-stage branches.

### Power, radio, integration and reuse (v0.19.0)

- `or.Electrical`:
  - **Power:** per circuit, the charge for pad + flight (`flightTime` from the simulation) + recovery against
    capacity x derating. FAIL above usable, WARN above 70% of it. E-match current I = V / (R_batt + R_match + R_wiring)
    against all-fire x margin; voltage while firing V - I R_batt against brownout.
  - **Radio:** FSPL = 20 log d + 20 log f + 20 log(4π/c) (exact constant; a test checks the textbook value). In
    flight, the farthest airborne sample (above 10 m) per branch and apogee; after landing the plane-earth loss
    40 log d - 20 log h1 - 20 log h2 beyond the crossover 4π h1 h2 / λ (free space inside it). `groundRange` bisects
    for the distance with the wanted margin.
- `or.ElectronicsSummary`: one reading of `electronics` in the standards (sensors, altimeter options, circuits,
  e-match, derating, durations, radio) shared by the six electronics tools, and one summary of every check used by
  `flight_card`, `design_status` (issues with the tool to open) and the review report (section 6).
- **Flying once:** `Sims.ensure` reuses a simulation's results when OpenRocket reports them up to date *and* a stamp
  matches: rocket modID, configuration modID, seed, launch-into-wind, aero table, plus each recovery device's
  deployment, each mount's motor and delay and each stage's separation. Those three setters fire no change event in
  OpenRocket 24.12, so without the stamp a deployment change kept the old flight (the benchmark caught it). Runs with
  extra listeners are never reused. `Variants.windCases` caches the ground-wind variants per simulation the same way
  (`check_requirements`, `design_status`, the report and the card share the design-wind flight). `ReuseTest` covers
  edits, deployment, motor delay, launch conditions, aero tables, undo and listener runs.
- **Results:** a 13-tool session on the example rocket went from 26.1 s to 3.1 s; `check_requirements` warm is about
  40 ms. Benchmark 137/137.
- **Found along the way:** working keys (`_cdA`) leaked from `search_parachutes`; `McpServer.withoutInternal` strips
  `_`-prefixed keys from every result. Times accept `min` and `h`.
- **Bug review before release:**
  - The wind-case cache was keyed weakly on the simulation, but each cached flight holds the document and so the
    simulation: closed designs were never freed (a team server would grow with every design opened).
    `Designs.close` now drops them (`Variants.forget`; `ReuseTest` checks).
  - One bad entry in `electronics` (a typo, `"9 V"` for 9) stopped the whole flight card. Each part of the electronics
    summary (sensors, power, radio) now fails alone into a note, and `design_status` lists it with `update_standards`.
  - Non-numbers, non-objects, negative battery resistance, fractional pyro channels, a zero or negative e-match
    resistance or all-fire current, and a non-positive derating, frequency or antenna height are refused by name
    instead of a Java exception or a silent wrong answer.
  - `altimeter_settings` found a device's branch by name and kept the last match; stages can share a name, so it now
    takes the branch holding the device's deployment event. `radio_link` numbers repeated branch names, and a part
    that never climbs above 10 m reports only its ground path.
- **Reproducibility note:** two server processes can differ in the 4th digit after long sessions. The design files
  match; one simulation result differs in the last bit (HotSpot's `Math` intrinsics differ between interpreted and
  compiled code), and the flight amplifies it. Within a process, and for a given call sequence on a fresh design,
  results repeat.

### Error reporting and physical limits (v0.19.1)

- **How it was found:** a fuzzer (every tool, every argument, valid baseline plus -1, 0, 10^30, text, wrong units,
  empty lists, objects where names go; about 4,000 calls) and a probe of part properties and list arguments. It found
  raw Java errors (malformed JSON, an empty motor class, CG outside a custom motor, thrust over OpenRocket's 10 MN,
  objects in name lists, duplicate wind levels, a 10^30 s pad time sizing an array), and several hundred non-physical
  values accepted (negative air density, zero Cd, negative parachute counts, 10^30 winds driving OpenRocket to NaN,
  negative shear modulus in the standards, booleans read from any text).
- `mcp.Limits`: one table of physical ranges by argument name, applied by `Args` to every number and quantity, with
  per-dimension defaults for names without a rule (no negative mass, area, volume, density, force, time; generous
  maxima). A name rule applies only to its own dimension ("width" is pixels for an image, a length for a shock cord).
  `Args.integer` refuses fractions, `Args.bool` refuses anything but true/false/yes/no, non-finite values are refused.
- `Components.physical`: negative sizes, masses, densities and Cd refused (positions, offsets, sweep and overhang may be
  negative); zero thickness/chord/span on fins, zero length/radius on tubes and nose cones, zero Cd/diameter on
  recovery devices refused; values OpenRocket clamps are read back and reported.
- `Standards.validate` on load and patch: shape against the defaults (a value object may be written as just its
  value) and every quantity through `Limits`.
- `Sims.problems`: OpenRocket's SIM_ABORT events (with advice per cause), its own run warnings of normal or high
  priority except high-speed deployment (left to `recovery_analysis`), Mach above 3 and peaks above 150 G. Shown in
  `run_simulation` (`PROBLEMS`, first), `check_requirements` ("Physically sound flight": FAIL when stopped, WARN
  otherwise; "Within the models' range" for notes on the model's validity), the flight card and the report.
  OpenRocket's "BUG: ... NaN" failure becomes an explanation.
- `McpServer.unexpected`: malformed JSON and unparsable numbers are the caller's to fix and say so; OpenRocket's own
  IllegalArgumentExceptions pass through as sentences; anything else is reported as a server bug with an error ID
  (logged with the stack trace).
- `Issues`: each error and result note also goes in `_meta["io.github.openrocketmcp/issues"]` with its kind: `input`
  (`ToolException`'s default; `field` from a quoted argument name, "not a valid <name>", or a quoted value given for
  one argument only), `physics` (`ToolException.physics`, and problems about the flight), `output` (non-finite numbers
  by path, problems about a model's range) or `system` (`ToolException.system`: network, external programs; and bugs,
  with `id` and a `report` of versions and stack trace).
- Tests: `LimitsTest`, `PhysicalTest` (refused edits, clamped values reported, a rocket that cannot lift off,
  OpenRocket's warnings), `IssuesTest` (kinds, fields, bug IDs and reports, result notes).

### Parachute sizes and product links (v0.20.0)

- `or.ParachuteSizes`: the setup for a device from the flight. Mass at deployment; density at the middle of the steady
  descent window (where `check_requirements` reads the rate); CdA_other = 2mg/(rho v_sim^2) - n Cd A (>= 0), so the
  curve passes through the simulated point. On the example rocket that is 0.18 m2 under the main (the drogue's CdA,
  still attached) and 0 under the drogue. `diameterFor` inverts the rate.
- `report.ChuteChart`: SVG with the first three slots of the validated categorical palette (light and dark), direct
  labels at the curve ends, a legend only for more than one curve, a dashed rule limit or shaded drogue band, a solid
  target, drop lines from each size-for-target point with the size above the axis (haloed text), hollow muted catalog
  dots with hover titles, and the current parachute. Rendered to PNG for the chat by Batik.
- `parachute_sizes` tool: table at round sizes per Cd (with landing energy for a main), catalog matches inside the rule
  limits nearest the target, `apply` / `diameter` to size the device and re-fly.
- `or.Products`: page reader (JSON-LD Product incl. `@graph`, meta tags, h1/title, visible text with table cells as
  "label: value"), maker from the page or the shop's address, kind (ThrustCurve link or designation + motor words;
  parachute words; part number in OpenRocket's presets; weight), and values with their source. Parachutes take Cd,
  weight and packed size from the catalog entry of the same maker and diameter whose description shares the most
  words when the page lacks them.
- `or.Web`: http(s) only; refuses loopback, site-local, link-local, CGNAT, ULA, multicast and *.local/*.internal hosts,
  checked again after every redirect (followed by hand, at most five); 3 MB, 20 s. `Web.use` swaps the client in tests.
- `Motors.fromThrustCurve`: ThrustCurve.org API v1 `search.json` then `download.json` (RASP, base64), written to
  `motors/` and loaded. `MotorTools.putMotor` is shared by `set_motor` and `add_from_link`.
- Tests: `ProductsTest` (constructed pages in `src/test/resources/products`, written to follow common shop page
  structures; not copies of real pages), `ProductToolsTest` (chart agrees with `check_requirements`, sizing flies the
  target, a link through a redirect, a pasted page, `apply: false`). The network policy of the development
  environment blocks shop sites and ThrustCurve.org, so live fetching was exercised only through the test client.

### Feature discovery (v0.21.0)

- Teams do not read documentation, so the server introduces itself while they work. `tools.Suggestions`:
  - `FEATURES`: about 50 features, each worded as a team member would ask, grouped by what they are doing. Every tool a
    team would ask for by name must have one (`SuggestionsTest` lists the plumbing tools that need none).
  - `NEXT`: what naturally follows each tool. `WHEN`: conditions (no av-bay suggestion when the design has one; the
    heating screen only above Mach 1.2; flutter above Mach 0.6 or when the Mach is unknown).
  - `suggesting` wraps every tool (outside the undo wrapper): on a successful map result it records the tool as used
    and adds `youCouldAlsoAsk` with up to two features not used and not yet offered in the session; each is offered
    once. `Context.toolsUsed` / `Context.suggested` hold the session state. Off with `"suggestions": false`.
  - `capabilities` tool: the whole map (or one group), marking used features; listing them counts as offering them.
- Server instructions: offer at most one or two per reply in the user's words, none while rule failures need fixing,
  never twice; call `capabilities` for new users. MCP prompt `what_can_you_do`: a tour fitted to the open design.
- Docs: the contributor guide asks for a physical range (`Limits`) for new argument names and a `Suggestions` entry for
  new tools; the methods resource covers the power budget, radio link, parachute chart and input checks.
- Cost: about 3% more tool output over the benchmark session. Tests: `SuggestionsTest` (every suggested tool exists and
  has wording, once per session, never for used tools, conditions, the standards switch). Two tests that compared a
  repeated call's full output now ignore the suggestions, which come with the first call only.

### Faster simulations and the interactive view (v0.22.0)

- **Efficiency pass** (profiled with Java Flight Recorder on the benchmark and the bundled designs):
  - `or.FastSim`: OpenRocket's engine with three per-step recomputations cached. `Simulation.simulate` builds its
    engine by reflection from a private class field; `FastSim.install` points it at `FastSim.Engine`, a
    `BasicEventSimulationEngine` whose RK4, landing and tumble steppers cache `calculateStructureMass` (key: the
    configuration object, its modification id, the rocket's modification id, the active stages) and
    `calculateMotorMass` (same key plus every active motor's state and its thrust-curve time, clamped at the last data
    point: OpenRocket's interpolation gives the same mass and CG past burnout and before ignition), and whose Barrowman
    calculator does `checkGeometry` once per configuration and stage subtree, replaying its warnings. Listeners that
    override the mass hooks turn the mass caches off. If OpenRocket's internals change, `install` leaves the standard
    engine in place. `FastSimTest` flies all 54 simulations of the bundled designs both ways: every flight-data value,
    event count and warning identical; 27 s -> 9 s.
  - Effect: benchmark 64 s -> 40 s; `monte_carlo` (benchmark) 10.9 -> 2.5 s; steady-state `roll_analysis` 3.6 -> 0.5 s,
    `compare_shapes` 3.2 -> 0.4 s, `optimize_fins` 3.4 -> 0.7 s, `optimize` 3.3 -> 0.85 s.
  - Renderer (`Raster3d`, `FlightAnimation`): triangles are filled over each row's covered span (computed from the
    linear barycentric weights, one pixel of margin) instead of the whole bounding box; 2x supersampling paths for the
    background copy and the box filter; the animation background without per-pixel allocation, with the field colour
    reused along a row. Pixel-identical output (compared on the 3-D views and the animation stills); default animation
    21 -> 16 s.
  - Missing files, permission errors and paths in the way now read as sentences instead of "internal error".
- **Interactive view** (`report.Workbench`, `tools.WorkbenchTools`, `resources/ui/workbench.html`):
  - MCP Apps 2026-01-26: `show_design` carries `_meta.ui.resourceUri` (+ the earlier flat key); `workbench_data` is
    `visibility: ["app"]`. The resource `ui://openrocket/workbench.html` (`text/html;profile=mcp-app`, `_meta.ui`:
    `prefersBorder`, empty CSP) is not listed in `resources/list`.
  - Data: per-part triangle meshes (float32, base64) from `Model3d` with OpenRocket's masses and editable properties
    (`Components.describe`), metrics, flight series in the team's units thinned to 1,200 points with events and peaks
    kept, CG/CP and the 3-D path (position, attitude, roll), dropped stages, events, wind and the rule checks (with the
    design-wind case). About 0.4-1.4 MB per design; it reaches only the view (`structuredContent` of an app-only call),
    while the model reads a summary line.
  - Views are snapshots (the last 8 per session) so a view scrolled back to shows what it showed; `refresh` rebuilds.
    Edits from the view go through `edit_components` and `undo` like any other call (and so through the undo history),
    and are reported to the model with `ui/update-model-context`.
  - Plumbing: `ToolDef.meta`, `CallContext.structured`, unlisted resources with `_meta`, the client's view capability
    (`McpServer.rendersViews`, wording only: app-only tools stay listed, since hosts hide them), CORS on the HTTP
    transport for `--allow-origin` origins.
  - Page: plain JavaScript, WebGL 1 (smoothed normals with a 40° crease, two-sided lighting, a fresnel highlight for
    the selection, colour-ID picking into an 8-bit texture, a cut plane facing the camera), SVG plots following the
    data-visualisation guide (one measure per plot, palette slots 1-3 validated for both themes, status colours only
    for status with icon and label), host theme variables and fonts, size reporting, full screen. The same page with
    the data embedded is the standalone file (`<\/` escaping inside the data block).
  - Checked against the MCP Apps reference host (`examples/basic-host` of modelcontextprotocol/ext-apps): handshake,
    data through the host, edit, refresh, model context, undo. `scripts/view_shots.mjs` checks every tab and view in
    headless Chromium (SwiftShader WebGL) and takes the documentation screenshots; all bundled designs load without a
    script error.
- Tests: `FastSimTest`, `WorkbenchTest` (meshes, series alignment, peaks, rail-clearance AoA, safe embedding, no
  network), protocol tests for metadata, structured content and unlisted resources, the `show_design` /
  `workbench_data` flow end to end (snapshot, edit and refresh, closed design), CORS.

### The flight video in the view (v0.23.0)

- **Fly view = the flight video, live** (`resources/ui/workbench.html`, `report.Workbench`, `FlightAnimation.plan()`):
  - `FlightAnimation.plan()` exports the scene the video stages: pad lift, length, radius, CG, rail length, launch
    axis, drift and side directions, burnout and apogee times, the main stack's sections, every nozzle (stage, side
    booster, position, radius), the dropped stages (track, stage, pivot span) and the pacing (animation seconds per
    flight second every 0.1 s). `sectionOf` tells the view which section each part hangs with (-1: a dropped stage).
    Stage tracks now carry attitude, roll and CG, so a dropped stage tumbles and pivots as in the video.
  - The page ports the video's staging to WebGL (`Replay`): attitude interpolated as a vector, the chase camera
    (elevation easing from -9° to +13° round apogee, pulled back at liftoff and at staging), flames sized by thrust,
    smoke ribbons that widen and fade with age (reaching the nozzle while it burns), canopies inflating over a second
    with alternating gore colours and shroud lines drawn to scale, the stack swinging apart under the newest canopy,
    dropped stages on their own tracks with their own chutes, and a stage camera (a second viewport) while one flies.
    Sky and ground are ray-cast per pixel like the video's background: fields with hedgerows, gravel round the pad,
    haze with distance, the sky darkening with altitude; hedgerows and fields fade to their average where a pixel
    covers more than one, so the far ground does not shimmer.
  - Cameras: chase (drag to swing, scroll to zoom), orbit (circles the rocket with playback), pad (a long lens from
    the ground) and whole flight (the trajectory's bounding sphere in frame, the rocket drawn larger and ringed). The
    flight path is drawn in the pad and whole-flight views only.
  - Playback "like the video" uses the exported pacing; 1x, 4x and 16x are fixed rates. Captions follow the video's
    timing (2.6 s of playback each, at most two).
  - *Make video* (in a host): `tools/call animate_flight` with the view's design and simulation; the panel lists the
    GIF and MP4 and shows the stills; the model is told through `ui/update-model-context`. Checked in the MCP Apps
    reference host (34 s for the Maple 10K with an MP4).
- **Video fixes found on the way** (`FlightAnimation`):
  - Stages that share a name each map to their own track (the lowest stage takes the first separation); before, the
    three-stage example's two "Booster stage" tracks both drew the bottom booster and the middle stage stayed on.
  - The main stack's extent ignores fins and motors sticking out aft, so no sliver section is made behind the
    airframe.
- Tests: `WorkbenchTest` (pacing, nozzles, sections and each part's section; the two-stage booster's track and parts),
  `FlightAnimationTest` (same-named stages, no sliver sections); `scripts/view_shots.mjs` drives every camera, a
  caption and playback in headless Chromium.

### Materials (v0.24.0)

- **`compare_materials`** (`or.MaterialStudy`): the chosen parts (`fins`, `body tubes` = the stages' own airframe
  tubes including a motor-mount chamber, `nose cone`, or component names) built from each candidate OpenRocket
  material, optionally at each stock thickness, and flown (ascent) on copies of the design in parallel.
  - Mass: OpenRocket's geometry x density. A mass override on the part, or on a parent overriding its subcomponents,
    moves by the computed difference, so a weighed part keeps the hardware its weigh-in includes.
  - Checks per option: fin flutter margin along the flight (`Structures.flutter`, shear modulus); fin root bending
    (`FinFea.rootStress` under `FinFea.designLoad`, strength / load safety factor); joint wall stress
    (`Loads.wallStresses`, new: the numbers behind `structural_loads`) against the strength; axial buckling of a thin
    cylinder, sigma_cr = gamma 0.605 E t / R with NASA SP-8007's gamma = 1 - 0.901 (1 - e^(-sqrt(R/t)/16)); and the
    adiabatic surface temperature (stagnation for nose and fins, turbulent recovery for tubes) against the service
    temperature, reported as CHECK since it is an upper bound.
  - Ranking: material passes, then the stability floor, then apogee. Missing data in the standards reads UNCHECKED
    (never a pass); every material's properties are listed with their sources. Tanks and cases: a note points to
    `pressure_vessel`.
- **`rank_motors`**: a mount wider than every motor (the airframe as the mount: a hybrid's 110 mm chamber) found no
  candidates, because candidates had to be within 1 mm of the bore. It now ranks the largest motors that fit and says
  so; `minDiameter` still chooses.
- Found by using the server as a new team would on a 4.5 in hybrid design (`MotorToolsTest`, `MaterialStudyTest`,
  benchmark checks on flutter versus material and thickness).

### Materials on the whole airframe (v0.24.1)

- `compare_materials` parts: `airframe` (nose cone + stage body tubes + transitions), `transitions` (boattail,
  shoulders; a nose cone, which OpenRocket also models as a transition, is kept apart) besides `fins`, `body tubes` and
  `nose cone`.
- Nose cones and transitions get the structural checks tubes had: wall stress at their joints on the part's own wall
  (the joint stress from `Loads.wallStresses` uses the thinner neighbour; scaled by t_joint / t_part), and buckling
  under the joint's axial compression: NASA SP-8019 for cones, P_cr = 0.33 x 2 pi E t^2 cos^2(alpha) / sqrt(3 (1 - nu^2)),
  alpha from the end radii and length, nu from structures.poissonRatio. Tested against hand values (with SP-8007's
  cylinder).
- Weigh-ins: a part weighed lighter than modelled now scales with the computed mass (it used to move by the
  difference and could clamp at zero, making two thicknesses weigh the same); heavier than modelled still moves by the
  difference.

### A clearer view and playback speed (v0.25.0)

- The design view reads at a glance in a chat panel and on a desktop: a larger type scale (14 px body, 22 px figures),
  headline tiles that wrap their labels instead of cutting them, the view controls in a toolbar above the 3-D scene
  instead of on it, the parts list beside its properties under the model at chat width, the flight readout as a grid
  of label / value cells, one plot per row at chat width (taller plots, event labels in ink), CG and CP labelled on
  opposite sides of the airframe with the margin above, rule checks as cards on narrow screens, and map labels with
  a surface halo that move away from crowded markers along a leader line. Below 900 px the page scrolls as a whole.
- Playback speed from 0.25x to 5x: a log-scale slider snapping to round steps, presets (0.25, 0.5, 1, 2, 5x), `[` and
  `]`, space to play and pause. The speed multiplies the video's pacing, or real time with *Video pacing* off; the HUD
  shows the resulting rate against real time.

### A truer 3-D model, and metric or imperial (v0.26.0)

- Exploded is `render_3d`'s layout, eased in by the slider: pieces apart along the axis (more between stages), fins
  sliding out, each internal part laid out below its piece in lanes (structure, then recovery and electronics, then
  motors), with dashed guides back to where it fits and names placed where they do not overlap. The server sends each
  part's offset at full explosion (`explode`), each fin's direction (`plateDirs`) and how far fins slide (`finOut`);
  side and 3-D views frame the whole layout.
- Cut-away: walls, rings and bulkheads read as solid sections (the inside of a cut part is drawn flat, a shade darker:
  closed meshes, so back faces are exactly the cut material); parts that hold others in the same tube (a sled, a
  hybrid's combustion chamber with its fuel grain) are opened too (`cutOpen`); fins toward the camera are left out
  whole instead of sliced along the cut.
- No flicker: parts entered wider than the bore they sit in are drawn inside it (following a nose cone's taper);
  shoulders fit inside the tube they plug into; walls entered with no thickness get one; nested parts draw with a depth
  offset by nesting level (`depth`), so a coupler in its tube or a motor in its mount never fights with it.
- Tube fins, launch lugs and rail buttons are drawn (in the view, `render_3d` and `animate_flight`).
- *Metric* / *Imperial* in the header: every number in the view is shown in one system (series, tiles, part
  properties, rule checks, captions), converted in the page from the data's units; the choice is kept in the browser.

### Aerodynamics per stage, with RASAero (v0.27.0)

- `aero_analysis stage`: the configuration with only that stage and those above it active (`Analysis.stack`), so CP,
  CNα, CD, the CG at launch and burnout and the reference diameter are the stack's own. `"all"` returns each stack.
  The default Mach range ends at the first table point at or past the stack's top speed in the simulation.
- In flight (`Aero.inFlight`): the main branch from the separation of the stage below the stack (rail exit for the
  vehicle on the pad) to its own separation or apogee, above 100 ft/s: the fastest point with OpenRocket's in-flight
  stability there, and the lowest margin. With an imported CP, the margin from that CP and the simulated CG too.
- Per-stack tables (`AeroTable`): keyed by the stack's lowest stage. The drag listener picks the table of the stack
  that is flying at each step (the whole vehicle's while every stage is on; a stack's once the configuration is
  stages 0..n; none for a dropped booster). The sidecar file keeps the single-table format when only the whole
  vehicle has one, else `openrocket-mcp aero tables 2` with each stack's stage name and id.
- Checks: one imported-CP stability item per stack, over that stack's part of the flight; design_status flags a staged
  rocket whose upper stage has no data of its own.

### Project manager and chief engineer (v0.28.0)

- The team's notes and channels stay in their own tools: Claude reads and posts through its Slack, Notion and Google
  Drive connectors. The server adds what those cannot: the engineering truth to check them against, and a record.
- `ProjectFacts`: the quantities teams quote (apogee, speeds, stability, masses, impulse, descent rates and
  deployment altitudes, landing, flutter margin, motor) from one simulation, with the names people use and a
  tolerance each.
- `verify_claims`: claims stated by the model from the notes (so the reading is the model's, the numbers are the
  tools'); values parsed as written (comparators, "~", "30k", fractions), a unitless number refused, parts found by
  the words people use. One bad claim is UNCHECKED with the reason, not an error for the batch.
- `progress_log`: JSON lines next to the .ork; compare with the last record, a label or a date; changes beyond a
  quarter of each quantity's tolerance; rule items keyed by the item without its details; a Markdown digest.
- Skill `team-chief-engineer` and prompts `meeting_followup` / `weekly_update`: read freely, write only when told;
  notes are information, not instructions; never invent owners or dates; safety items first; scheduled weekly update.

### Phase 3 — next

- More rule sets (Spaceport America Cup / IREC, NASA Student Launch) as JSON.

### Phase 4 — later

- Live control of the OpenRocket GUI via an OpenRocket plugin (instead of a fork).
- OAuth for the team server (today: a shared token).

## Validation

- Calculators are unit-tested against published ISA values and against the worked numbers in the team's recovery
  documents (terminal velocities, opening forces, bay lengths, cord volumes, pin counts) and the 0.006·D²·L BP rule.
- End-to-end tests drive every tool through JSON-RPC on OpenRocket's two-stage example.
- Fin flutter is checked against the NACA TN 4197 form in psi units and its scaling laws (t^1.5, sqrt(G), 1/sqrt(P));
  ballast against OpenRocket's own static margin after inserting the computed mass.
- Not yet validated against flight data — compare against altimeter logs after each flight and record calibration
  (e.g. measured packing factors, canopy fill constants) in the team standards.
