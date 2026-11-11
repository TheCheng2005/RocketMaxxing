# Technical reference

For teams and developers who want the details: every tool of the engine (the analyses on RocketMaxxing's Analysis
page, and the tools an AI assistant calls through the MCP server), the settings in the team standards file, the
methods behind the numbers and their limits, and how to build and test the server. The app's guide is
[ROCKETMAXXING.md](ROCKETMAXXING.md), the guide for AI assistants [MCP.md](MCP.md), and the [README](../README.md)
points to everything.

## Tools

The 86 tools, grouped by what a team is doing (one of them, `workbench_data`, serves the interactive view and is hidden
from the model by hosts that show views). Bold marks the main job of each tool.

### Session

- `design_status`: **where the design stands**. It gives the rule-check summary, then what is not set up yet: motor,
  deployment, electronics, RASAero data where the diameter changes, team standards and launch site. It also lists mass
  overrides and unsaved changes, and the next steps in order with the tool for each.
- `capabilities`: **what the team can ask for**, grouped by what they are doing (start and review, design and parts,
  motors and flight, stability, structures, recovery, electronics, launch day, after the flight, liquid and hybrid
  programmes, pictures), each
  worded as a team member would ask it, with the tool and whether it was used in this session. `group` narrows it.
- **Suggestions as you work:** a result may end with `youCouldAlsoAsk`, up to two related things the team has not used
  yet (after `recovery_analysis`: plot parachute size against descent rate; after `run_simulation` of a fast rocket:
  flutter, heating). Each is offered once per session, only when it applies (no av-bay layout when there is one, no
  heating screen below Mach 1.2). The server instructions ask the model to offer at most one or two per reply, in the
  user's words, and none while rule failures need fixing. `"suggestions": false` in the standards turns them off.
- `undo` / `redo` / `history`: every tool call that changed the rocket is one step, up to 50 steps. Motors, deployment
  and all component properties are included. Files are untouched until `save_design`.

### Interactive view

- `show_design`: **the design and its flight as an interactive view** (MCP Apps). In Claude Desktop and claude.ai it
  opens in the chat; it is also written as a standalone HTML file (`path`, default `<design>-view.html`) with the data
  embedded, to open in any browser or send to the team. The model reads only the headline numbers and the rule-check
  summary; the bulky data goes to the view. `tab` picks the first tab; the simulation and launch overrides are those of
  `run_simulation`.
  - **Rocket:** WebGL model built from the design's geometry (every part and motor, tube fins, launch lugs and rail
    buttons, OpenRocket's masses). Orbit, zoom and pan; *Cut-away* removes the half of the airframe toward the camera,
    drawing the cut walls, rings and bulkheads as solid sections and opening any part that holds others (a sled round
    its altimeters); fins on the near side are left out whole rather than sliced. *Exploded* is the `render_3d` layout,
    eased in by the slider: the airframe pieces pull apart along the axis, the fins slide out, and every part inside is
    laid out below the piece it goes in, named, with a dashed guide back to where it fits. *Side* is an orthographic
    view to scale with a ruler (and works exploded too). Parts entered wider than the tube they sit in (a shoulder as
    wide as the airframe, a mass item wider than the bore) are drawn inside it, and nested parts are drawn so touching
    surfaces do not flicker. *Metric* / *Imperial* in the header switches every number in the view (m, mm, kg, N, m/s
    or ft, in, lb, lbf, ft/s), whatever the team setting, and is remembered in that browser. CG and CP
    markers and the margin follow the time cursor (from the simulation). Clicking a part (or its row in the parts list)
    highlights it and lists its properties; in a host, clicking a value edits it through `edit_components` (enum and
    yes/no values become lists), re-simulates and updates the view; *Undo* calls `undo`. The model is told of selections
    and edits (`ui/update-model-context`); *Ask Claude about this part* sends a message to the chat.
  - **Fly:** the flight as `animate_flight` stages it, live and interactive: the pad and rail, fields to the horizon
    with haze, the exhaust flame at the burning nozzles and the smoke trail, the parachutes inflating with their gores
    and shroud lines, the stack hanging apart under them at its separation joints, dropped stages on their own tracks
    (with a stage camera in the lower left while one flies), event captions and a trajectory map. Position, attitude
    and roll are OpenRocket's. Cameras: *Chase* (the video's, across the drift), *Orbit* (circles the rocket),
    *Pad* (a spectator by the pad with a long lens), *Whole flight* (the trajectory, the rocket drawn larger and
    ringed); drag to swing the camera, scroll to zoom, double-click to reset. *Play* (or space) runs it at any speed
    from 0.25x to 5x (slider, presets, `[` / `]`), times the video's pacing (real time through the burn, slowed round
    apogee and each deployment, the rest sped up) or, with *Video pacing* off, times real time. In a host,
    *Make video* runs `animate_flight` on the same design and simulation and shows the key-moment stills; the GIF and
    MP4 are written beside the design. The scene comes from the video's own code (`FlightAnimation.plan()`: pad, rail,
    sections, nozzles, dropped stages, pacing), so the two agree.
  - **Flight plots:** altitude, speed (total, vertical), acceleration, Mach, stability (with the rule minimum), thrust
    and drag, angle of attack (from rail clearance) and mass, one measure per plot, events marked. One time cursor
    across all plots with a readout of every value; drag to zoom, *Ascent* / *Whole flight* ranges. Up to 1,200 points
    per series (every event and peak kept).
  - **Trajectory:** altitude against distance downrange and the ground track (equal scales, wind arrow), apogee,
    deployments and touchdown marked, dropped stages dashed.
  - **Rule checks:** every check with status (icon and label), value, requirement and reference, failures first, with a
    filter; in a host, *Ask Claude how to fix* on failures and warnings.
- `workbench_data`: what the view loads (`_meta.ui.visibility: ["app"]`). Each `show_design` keeps a snapshot (the last
  eight), so a view scrolled back to in the chat shows what it showed; `refresh: true` rebuilds from the design as it is
  now. If the design is no longer open, the view says to ask for it again.
- **Protocol:** MCP Apps (2026-01-26): `show_design` links `ui://openrocket/workbench.html` in `_meta.ui.resourceUri`
  (and the earlier `ui/resourceUri` key); the resource (`text/html;profile=mcp-app`, not listed in `resources/list`)
  needs no network: scripts, styles and data are all in the page. The view speaks `ui/initialize`, receives the tool
  input and result, calls tools through the host, reports its size, follows the host's theme and display mode, and asks
  for full screen. Tested against the MCP Apps reference host.

### Designs

- `open_design`: opens a .ork file, a bundled example or a new design; `list_designs` shows what is open and
  `close_design` frees one.
  - **Older designs:** `.ork` files from any OpenRocket version open.
  - **RockSim:** `.rkt` files open as imports. The original is never overwritten; save to a new `.ork`.
  - **Conversion warnings:** whatever OpenRocket changed or dropped while reading an older or foreign file is listed
    as `conversionWarnings`, to check before trusting the numbers.
  - **Custom materials:** materials carried inside the file can be named in `edit_components`.
- `get_design`: the component tree, motors, and the **stability of every stage stack**.
- `describe_component`, `edit_components`, `add_component`, `remove_component`: read and change parts.
- `set_deployment`, `set_stage_separation`, `flight_configuration`: events and configurations.
- `save_design`: writes the .ork (and the aero table next to it, if one is imported).

### Parts and drawings

- `search_parts` / `apply_preset`: OpenRocket's manufacturer parts database for body tubes, nose cones, couplers, rings,
  bulkheads, rail buttons, launch lugs and chutes.
- `add_from_link`: **a product page into the design**. Give the link (`url`) or paste the page's text (`pageText`)
  when the server cannot reach the shop.
  - **Parachute:** diameter, Cd, weight and packed size from the page. A matching catalog parachute (same maker and
    diameter, closest description) fills in what the page leaves out. Replaces `component` or is added under `parent`.
  - **Motor:** the designation in the title (delay suffixes such as "-L" are dropped) or a ThrustCurve.org link. Motors
    newer than OpenRocket's database are downloaded from ThrustCurve.org into `motors/`. Goes into the only motor mount
    or `component`, with a note when it needs an adapter.
  - **Catalog part:** a tube, nose cone, coupler or ring by the part number on the page.
  - **Item to carry:** an altimeter, tracker, battery or hardware by its weight (and longest dimension), into the
    av-bay by default.
  - **Every value names its source** (page, catalog, given, assumed). `cd`, `diameter` and `mass` override the page.
    `apply: false` only reports. The rocket is re-flown and the effect shown; undo reverts it.
  - **Safety:** only public http(s) pages are fetched. Links to the server's own machine or a private network
    (localhost, 10.x, 192.168.x, link-local, cloud metadata) are refused, also after a redirect. Pages are capped at
    3 MB and 20 s. Shops that block automated access get a message to paste the page instead.
- `draw_rocket`: a **cut-away** SVG from OpenRocket's geometry. It draws the outside profile and fins over the internals,
  colour-coded by kind: parachutes and shock cords, altimeters, batteries, trackers, ejection charges, switches,
  ballast, motor, couplers, rings and bulkheads. It also marks separation points, CG and CP. The report includes it.
- `render_3d`: a **shaded 3-D picture** (PNG) of the rocket.
  - `exploded` (the default): the airframe pieces pull apart along the axis and the fins slide out. Every internal part
    sits below the piece it goes in, with a dashed line to where it fits.
  - `cutaway`: the near half of the airframe, couplers and motor mount is removed, so the parts show in place.
  - `assembled`: the outside only.

  Numbered balloons key each part to a **parts list with OpenRocket's masses**, as on an assembly drawing. Good for
  design reviews, build guides and posters.

  For a staged rocket the stages sit apart. A bracket over each stage gives its name and total mass (motor
  included), and every part in the list carries its stage. Side boosters count with the stage they are attached to.

### Flight animation

- `animate_flight`: a **3-D animation of the simulated flight**, written as a looping GIF, an H.264 MP4 and a sheet of
  stills at the key moments, which is shown in the chat. A `path` ending in `.mp4` writes the MP4 alone (`gif: true`
  adds the GIF).
  - **Camera:** a chase camera follows the rocket, built from the design's geometry and rolling as simulated. While
    the motor burns there is an exhaust flame, and a smoke trail stays in the sky.
  - **Recovery:** at each deployment the airframe comes apart at its separation joints and hangs under the canopy as
    it inflates. Drogue and main have their own colours.
  - **Staged rockets:** each stage follows its own simulated flight.
    - The booster rides on the vehicle until its separation event. At staging the camera pulls back to show it
      falling away.
    - The flame moves to the sustainer when it lights. Side boosters burn with the core.
    - A **booster camera** in the lower left follows the dropped stage down under its own chute to touchdown, with
      its altitude and speed.
    - The trajectory inset draws each dropped stage's path and landing point in orange.
  - **On screen:** the flight clock, altitude, velocity, vertical speed, Mach, acceleration, distance from the pad and
    wind at the rocket's altitude.
  - **Captions:** liftoff, rail clear, Mach 1, maximum velocity, burnout, stage separation and ignition, apogee, each
    deployment and touchdown.
    - On a staged rocket they name the stage: booster burnout, booster separation, sustainer ignition, sustainer
      burnout, the booster's chute, booster touchdown and sustainer touchdown.
    - Insets show a 3-D trajectory and the altitude trace. A timeline marks every event, and a flight summary (every
      stage's burnout, deployments and landing) closes the animation.
    - The stills sheet picks up to six distinct moments: liftoff, stage separation, apogee, the main, touchdown, then
      others.
  - **Timing:** playback is real time through the burn and slows around apogee and each deployment. The coast and
    descent are sped up to fit `duration` (25 s by default), and the playback rate is always on screen.
  - **Live:** the same scene plays in `show_design`'s *Fly* view, where the camera can be moved and the flight scrubbed;
    *Make video* there calls this tool.
  - **Staging and sections:** stages that share a name (two "Booster stage"s) each get their own track, the lowest
    first; the stack comes apart at the joints of its airframe (fins or a motor sticking out aft make no section).

### Motors

- `search_motors`: filter by diameter, class, **certification level** and manufacturer.
- `rank_motors`: **simulates the candidates** for target apogee, maximum apogee, or the smallest motor that meets the
  rail-exit rules. Candidates match the mount's bore; when no motor does (the airframe itself as the mount, e.g. a
  110 mm hybrid chamber), the largest motors that fit are ranked and the result says so (`minDiameter` picks another
  size).
- `set_motor`: includes air-start ignition.
- `import_motor_file`: .eng or .rse files.
- `simulate_engine`: **an engine's burn predicted from its design**, before it is fired: `type` solid, hybrid or
  liquid; the nozzle (`throatDiameter`, `exitDiameter` or `expansionRatio`); `cstar` (or `cstarTable` against
  mixture ratio) and `gamma` from a thermochemistry code (NASA CEA, RocketCEA, ProPEP); `cstarEfficiency` (0.95;
  hybrids 0.90) and `nozzleEfficiency` (0.97). Solid: BATES grains (`grainCount`, outer, core, length,
  `inhibitedEnds`), `propellantDensity`, `burnRateCoefficient` and `burnRateExponent`. Hybrid: `oxidizerMass`, the
  injector (`injectorHoleDiameter`, `injectorHoleCount`, `dischargeCoefficient`, `oxidizerDensity`, `tankPressure`,
  `tankPressureEnd`) or `oxidizerFlow`, the fuel grain (`portDiameter`, `portCount`, `grainOuterDiameter`,
  `grainLength`, `fuelDensity`) and `regressionCoefficient`, `regressionExponent`. Liquid: `oxidizerMass`,
  `fuelMass`, `mixtureRatio`, `chamberPressure`, `feed` regulated or blowdown (`ullageFraction`,
  `polytropicExponent`). Returns impulse and class, burn time, thrust, delivered Isp, chamber pressure, mass flow and
  mixture ratio through the burn, what is left unburnt, the nozzle against the outside air, a plot, and warnings
  (flow separation, port flooding, low injector pressure drop, erosive burning). With `designation`,
  `manufacturer`, `diameter`, `length` and `dryMass` it becomes a motor whose mass and CG follow the burn
  (`dryCgFromTop`, `oxidizerCgFromTop`, `fuelCgFromTop`). With `designId` the engine is flown in that rocket (the
  design is not changed) and the thrust follows the air pressure along its flight.
- `create_custom_motor`: **liquid, hybrid and static-fire thrust curves**, with the tank CG shift. A static-fire log goes in
  as the test stand wrote it (`staticFireFile`, or its text as `staticFireCsv`; columns and units from the header, or
  `timeColumn`, `thrustColumn`, `timeUnit`, `thrustUnit`): the load cell's offset is removed (the median before
  ignition; a straight line to the median after the burn when the log goes on, since load cells drift as the stand
  heats), the burn is taken from the first to the last time thrust is above 5% of peak (certification practice),
  noise is averaged over 10 ms, and the curve is reduced to 200 points scaled to the full-rate total impulse. The
  answer reports what was found (offset, noise, burn, impulse, Isp from the propellant mass) and warns about a log
  that starts mid-burn, an offset that shifted by more than 1% of peak, or too few samples.

### Flight

- `run_simulation`: apogee, Mach, rail exit speed, thrust-to-weight, minimum and maximum stability, events per stage,
  deployments and landing distance.
- `get_flight_data`: down-sampled time series, e.g. for stability-against-time plots.
- `sweep`: vary launch conditions or any component property and fly each value.

### Aerodynamics and wind

- `aero_analysis`: OpenRocket's aerodynamic model queried directly. It gives CD split into friction, pressure and base,
  plus CP, CNα and static margin against Mach, the **drag of each component** and OpenRocket's geometry warnings. The
  Mach range runs to just past the simulated top speed.
  - **Per stage:** `stage` gives what is left flying once the stages below it separate, e.g. the sustainer alone (its
    own fins, CG and reference diameter); `"all"` gives every stack. Each stack also gets its flight: when it flies,
    its top Mach, the margin there (OpenRocket's, with the simulated CG) and its lowest margin. A supersonic sustainer
    usually loses margin past Mach 1.2 as its fins lose lift slope, and it is often the fastest part of the flight, so
    this is the case to check.
  - **With RASAero:** a table imported for the stack is shown beside OpenRocket's numbers at each Mach (CD power-off
    and power-on, CP, and the margin with that CP and the same CG), and in the flight summary.
- `wind_profile`: **winds aloft**. It sets OpenRocket's multi-level wind model from forecast or sounding levels, or from
  a power-law shear profile. Every tool then flies in it.

### Goals and dispersion

- `optimize`: goal-seek 1 to 3 properties. Objectives are target apogee, maximum apogee, target stability or minimum
  mass. Constraints are stability, rail exit, Mach and apogee. It reports the fin flutter margin of each design; with
  `meetRules` or `minFlutterMargin` it only accepts fins that meet it (materials of unknown stiffness are not
  constrained).
- `ballast`: how much nose weight a stability target needs, refined by simulation.
- `monte_carlo`: randomized wind and launch angle, with optional **structure mass, drag, thrust and parachute Cd
  uncertainty**. It returns:
  - the landing ellipse per stage and the apogee spread;
  - the worst stability and rail exit speed;
  - the worst deployment airspeed and opening load;
  - **which inputs drive the spread**.

  `kmlPath` writes a Google Earth file (see below).

### Fin design

- `optimize_fins`: the **whole trapezoidal planform at once**: root chord, taper, span and sweep, optionally with a list
  of stock thicknesses. It can aim for maximum apogee, minimum mass or a target apogee. Candidates must meet:
  - the stability floor and ceiling from the rules, also in the maximum wind;
  - the rail exit speed;
  - the flutter margin.

  Only buildable shapes are tried: a minimum tip chord, no tip trailing edge behind the root unless allowed, a capped
  sweep, and a root no longer than the tube. It draws the planform; `apply` keeps the result.

### Design studies

- `compare_shapes`: a **nose cone and fin shape trade study**. It flies every nose profile (optionally at several
  lengths) and every fin edge profile in OpenRocket. It reports apogee, CD at the design Mach, stability, mass and
  guidance.
- `recovery_sections`: **tethered sections from the design**. Per section it gives landing mass, velocity and kinetic
  energy, the energy if the main fails, the bay fill and a black powder estimate.
- `structural_loads`: **axial and bending loads at every joint**, for boost and for max q with a gust. It includes
  inertial relief, wall stress and margin. `csvPath` writes the load cases for tube and coupler FEA.

### Mass

- `mass_budget`: **mass budget and weigh-in tracking**.
  - **Input:** CSV text, a CSV file or a list of lines. Columns are part, mass (units in the cell or the header),
    optional CG (from the part's front or from the nose), status, qty, section and parent.
  - **Matching:** each line is matched to a component by id, name or a similar name.
  - **Comparison:** model against budget per line, largest differences first. Weighed sections are counted once, and
    model parts the budget leaves out are listed. It projects dry and launch mass, adds contingency on everything not
    weighed, and gives the margin to a target launch mass.
  - **`apply`:** writes mass and CG overrides into the design:
    - weighed sections become section overrides;
    - per-copy components are rescaled so the set weighs the number given;
    - several lines for one part are summed;
    - parts the design lacks are added as mass components in their parent.

    It warns when an enclosing section override hides a part, and reports stability before and after.
  - **No budget yet:** it writes the model's breakdown as a CSV template.

### Avionics

- `add_avionics_bay`: an **electronics bay** between two airframes. It adds:
  - a coupler with a switch band, bulkheads and a sled;
  - one altimeter per circuit, each with its own battery and switch;
  - a GPS tracker with its own battery, in the nose or the bay (it warns about RF-blocking airframes);
  - four ejection charges on the bulkheads, each facing the bay it opens;
  - U-bolts and static port sizing.

  It packs the shock cords and parachutes against the bay, and warns when they do not fit before the nose shoulder or
  motor mount. `check_requirements` then counts altimeters and batteries against the electronics edicts.
- `sensor_data`: **what the flight computer would log**, as CSV at a fixed rate (default 100 Hz), to test flight
  software before it flies.
  - **Accelerometers** read specific force in the rocket frame: x along the axis toward the nose, y in the pitch plane.
    That is +1 g on the pad, thrust minus drag in flight, and the canopy's pull under parachute. Gravity is not sensed.
  - **Gyroscopes** read the body rates; the **barometer** reads ambient pressure and temperature.
  - **GPS** gives fixes at its own rate, and none above the 515 m/s / 18 km export limits.
  - **Sensor behaviour:** each sensor adds its noise, rounds to its resolution and clips at its range. The same seed
    gives the same file.
  - **Extras:** pad time before ignition, rest after landing, the true values (`truth_` columns), and the true event
    times in `<name>-events.csv` to score launch, burnout, apogee and main detection. A stage can be logged on its own
    (`branch`).
- `sensor_check`: **each sensor's range against the flight**.
  - **Accelerometers:** peak axial acceleration (boost and coast, and parachute openings separately) and lateral.
  - **Gyroscope:** roll, pitch and yaw rates.
  - **Barometer:** the lowest pressure, with the altitude where a barometer runs out.
  - **GPS:** the export limits.
  - **Mach window:** when static ports cannot be trusted near Mach 1.
- `altimeter_settings`: **altimeter settings from the simulation**.
  - **Per recovery device:** the primary and backup setting. By default the drogue backup fires 1 s after apogee and the
    main backup 100 ft lower. You also get the airspeed the backup drogue fires at, and how long the backup main waits.
  - **Mach lockout:** time above Mach 0.7, plus 1 s.
  - **Static ports:** sized for the bay.
  - **Card:** optionally a one-page Markdown card with a pre-flight checklist.
- `power_budget`: **every battery against the day**. For each circuit (by default the team's: two altimeters and a GPS
  tracker), the charge needed for the pad wait + the simulated flight + the search, against the derated capacity
  (80% by default); the runtime it gives; the current through each e-match (battery voltage over battery + match +
  wiring resistance) against the all-fire current times a margin (2x); and the voltage while firing against the
  brownout voltage. FAIL when it runs out or a match may not fire, WARN under 30% spare or on a brownout. Pass your
  own `circuits`, `padWait`, `recoveryTime`, `ematchResistance` or `allFireCurrent`.
- `radio_link`: **can the ground station hear the tracker**. The link budget (transmit power + antenna gains −
  sensitivity − losses) against the path loss at the farthest point of each flight branch (free space in the air), at
  apogee, and after landing (plane-earth two-ray loss, since both antennas sit near the ground), with the range at
  which a landed tracker still has the wanted margin (10 dB). The ground station may stand away from the pad
  (`groundStationEast` / `groundStationNorth`); `landingDispersion` adds the Monte Carlo spread to the landing.
- **Electronics everywhere:** the flight card and the review report carry an Electronics section (altimeter settings,
  sensor problems, every battery circuit and the radio), and `design_status` lists electronics problems with the tool
  to open.
- **Team sensors, altimeter choices, batteries, e-matches and radio** live in `electronics` in the team standards. The defaults are typical student
  parts (±16 g and ±200 g accelerometers, ±2000 deg/s gyro, a 30–125 kPa barometer, GPS): replace them with yours.

### Recovery chain

- `recovery_analysis`: deployment airspeed, air density and mass from the simulation, then the opening load (Knacke Cx
  and finite-mass inflation), then the shear pins.
- `deployment_delay_sweep`: how late can the drogue fire?
- `size_parachute`: the canopy size for a descent rate, plus real chutes from the parts database.
- `parachute_sizes`: **descent rate against canopy diameter**, as a chart (attached; SVG with `plotPath`) and a table.
  - **Curves:** one for each Cd you choose (`cd`, or up to three in `cds`); default the parachute's own Cd.
  - **From the design:** the mass is the simulated mass under the device (default the main). The drag already there
    without the canopy (the airframe, a drogue still attached under the main) is calibrated so the curve passes
    through the simulated rate of the parachute as it is now, read where `check_requirements` reads it.
  - **On the chart:** the rule limit (main maximum) or band (drogue), the target (default the rule set's recommended
    rate), the size each Cd needs for it, every catalog parachute at its own Cd (hover for the part), and "now".
  - **Sizing:** `apply` sets the parachute to the size for the target at the chosen Cd (rounded up to a whole inch,
    or cm in metric units) and that Cd, then re-flies; `diameter` sets a size of your choice. Packed size and mass
    stay as they were (take them from the vendor, or `apply_preset` a catalog match). Undo reverts it.
  - Without a design, give `mass` (and `otherCdA`, `role`).
- `search_parachutes`, `opening_shock`, `shear_pins`, `ejection_charge` (black powder).
- `recovery_bay_fit`: bay volume from the design, in a tube or a nose-cone interior.
- `descent_energy`: landing energy per section.

### Structures

- `fin_flutter`: the flutter speed of every fin set along the simulated flight (NACA TN 4197 with the corrected
  constant). It gives the worst margin and the thickness or shear modulus that fixes it. `check_requirements` runs it too.
- `compare_materials`: a **material trade study**. `parts` is `fins`, `airframe` (the whole aerodynamic body: nose
  cone, body tubes and transitions), `body tubes` (the stages' airframe tubes), `nose cone`, `transitions` (boattail,
  shoulders) or component names; `materials` are OpenRocket material names (default: common choices for those parts), and
  `thicknesses` adds stock sizes. Each option is flown on a copy of the design and reports:
  - launch mass, apogee, ascent stability and rail exit (mass from geometry x OpenRocket's density). A weighed part
    keeps its weigh-in: heavier than modelled, the override moves by the computed difference (the hardware it includes
    stays); lighter than modelled, it scales with the computed mass;
  - fin flutter margin (shear modulus) and fin root bending (strength, the `fin_fea` hand estimate);
  - wall stress of tubes, transitions and the nose cone at their joints (`structural_loads`, on the part's own wall) and
    shell buckling (Young's modulus): a cylinder with NASA SP-8007's knockdown, a cone (nose, transition) with NASA
    SP-8019's;
  - the surface temperature against the material's service temperature (a CHECK, since it is an adiabatic bound).

  Options whose material passes come first, then those meeting the stability floor, by apogee. A material without
  data in the standards reads UNCHECKED rather than passing. Moduli, strengths and temperatures come from
  `structures.*` in the standards with their sources: put your coupon data there. Tanks and motor cases are sized by
  pressure (`pressure_vessel`), not by these flight loads.
- `fin_fea`: a **finite-element check of a fin with CalculiX**.
  - **Model:** 8-node shells clamped at the root, with in-plane engineering constants from the team standards.
  - **Load:** the worst flight load (a gust at max q, or the largest simulated q × angle of attack) on the most loaded
    fin.
  - **Results:** tip deflection, stress margin with the load safety factor (the corner peak is reported apart), and
    natural frequencies labelled bending or torsion, with hand estimates alongside.

  The `.inp` deck is always written and also opens in Abaqus and PrePoMax. `plotPath` draws the fin coloured by stress.

### Heating and roll

- `aero_heating`: stagnation and recovery temperature at the nose tip, fin leading edges and body along the flight,
  against each material's service temperature, plus the Sutton-Graves nose-tip heat flux and heat load.
- `roll_analysis`: a fin cant / misalignment sweep. It gives the roll rate, the roll at burnout, the pitch-frequency
  crossing and the alignment tolerance.

### CFD, CAD and manufacturing

- `export_geometry` writes three things:
  - the **outer mold line as STL**, with a named region per part, the base and each fin set. The body is watertight and
    the fins are sunk into it. It works with OpenFOAM, SimScale, Fluent, STAR-CCM+ or CAD.
  - **fin flat patterns as DXF**, with tabs.
  - a **CFD run matrix** from the simulated flight. Each Mach number is paired with the altitude and air state where the
    flight reaches it, plus max q and max Mach, the Reynolds number, and OpenRocket's CD and CP for comparison. It comes
    with a results template that `import_aero_table` reads back.

  It also draws a shaded 3-D preview of the regions as SVG.

### External data

- `import_aero_table`: a **RASAero II** aero export, or any Mach/CD/CP CSV. Its drag replaces OpenRocket's in every
  simulation, power-on and power-off. Its CP is used for a stability check against the simulated CG, as Launch Canada
  asks for diameter changes. On a staged rocket each table is for one stack: the whole vehicle by default, or with
  `stage` what flies once the stages below separate (a RASAero run of the sustainer alone). Each stack's table applies
  while that stack flies, and check_requirements checks each stack's CP over its own part of the flight. A dropped
  booster, and any stack without a table, fly on OpenRocket's model. `show` lists the tables; `clear` with `stage`
  removes one. save_design keeps them all next to the .ork.
- `compare_flight`: an **altimeter log against the simulation**. It compares apogee, time to apogee (from lift-off to
  the top of a parabola through the samples around apogee), and drogue and main descent rates (the main's below 35% of
  its deployment altitude, past the canopy opening), draws an overlay plot, and finds the drag factor that reproduces
  your flight. When the log has an **accelerometer** column, it integrates it for the burnout speed and reads the coast
  drag, fits thrust and drag separately, splits the apogee difference between them and gives each version's RMS error
  over the climb, with plots of the boost and the climb. An apogee alone cannot tell a weak motor from extra drag; see
  the [worked example](WORKED_EXAMPLE.md).

### Launch day

- `rail_buttons`: **rail button placement**. The rocket is guided until the aft button leaves the rail, so every cm the
  aft button sits above the aft end is rail it never uses. OpenRocket counts the whole rail and ignores rail buttons, so
  its rail exit velocity is optimistic.
  - **Aft button:** as far aft as a body tube allows, over a centering ring or bulkhead when one is close. A button the
    team already has lower down stays where it is.
  - **Forward button:** where the pointing error at rail exit is least. Tip-off grows with the spacing: while the
    rocket hangs on the aft button alone, gravity across the tilted rail and the crosswind on the CP pivot it. Slop in
    the rail slot shrinks with the spacing.
  - **Returns:** both positions, the effective rail exit velocity, tip-off rate and angle, slop, side loads on each
    button, and the change from the current layout.
  - **Check or apply:** `forward` / `aft` check a layout of your own; `apply` moves the design's buttons there (or adds
    a Delrin pair), and undo reverts it.
- `weather_forecast`: a **site forecast by GPS coordinates** from Open-Meteo: ground wind, gusts, temperature, pressure
  and winds at pressure levels up to the jet stream. It is applied to the simulation as a wind profile, with the site's
  altitude, temperature and pressure. Without internet, paste the JSON, or enter winds by hand with `wind_profile`.
- `flight_card`: a one-page launch-day card. It covers the vehicle, CG/CP, motors with the optimum and closest
  available ejection delay, predictions, recovery settings, sections and landing energy, drift per ground wind, the
  rule check, the electronics (altimeters, power, radio) and sign-off lines.

### Reviews and files

- `design_library`: the **team's past rockets and flights as a searchable history**. Point it at a folder of .ork
  files (any OpenRocket version) and RockSim .rkt files, sub-folders included, with altimeter logs (.csv) beside them.
  - **Per design:** year (from a folder or file name like `2024/`, else the file date), diameter, length, launch mass,
    motors and impulse class, stages, predicted apogee (the file's saved simulation, else simulated now), stability,
    materials and recovery. Files are read without being opened, several at a time, and summaries are cached until a
    file changes. Linked files are not followed (on the team server a link could lead outside the workspace).
  - **Search:** text, diameter range, motor class (`M`, `L-N`), years, stages, or only rockets that flew. For example,
    "our 4 in rockets on M motors" is `minDiameter "3.9 in"`, `maxDiameter "4.1 in"`, `motorClass "M"`.
  - **Similar rockets:** `similarTo` ranks past designs by closeness to an open one (diameter, mass, impulse).
  - **Predicted vs measured:** each log is matched to its design (file name first, then its folder). Simulation data
    exported from OpenRocket or this server is recognised and not counted as a flight. The table shows
    every flight's prediction error, the mean by year and the overall bias.
- `compare_designs`: a **design diff** against another open design, another .ork, or an earlier **git revision** of the
  same file.
  - It compares length, mass, CG, CP, stability, motors, apogee, velocity, Mach, rail exit, flight stability, descent
    rates, landing, and every rule check whose status changed.
  - Component edits are matched by OpenRocket's persistent ids, and both designs fly in the same conditions.
  - It can write a Markdown summary, and warns when an edit is hidden by a mass override.
- `list_files`: designs, motor files, tables and reports in the workspace.

### Project and team

The project side of a chief engineer's job. Reading notes and posting updates go through the client's own connectors
(Slack, Notion, Google Drive); these tools supply the engineering. See [TEAM.md](TEAM.md).

- `verify_claims`: **what the notes say, checked against the design.** The model reads the meeting notes, messages or
  documents and states each number or choice as a claim; the tool compares it with the design and its simulation.
  - **Quantities**, under the names people use: apogee, time to apogee, max speed, Mach, max acceleration, rail exit,
    minimum and maximum ascent stability, static margin, liftoff and burnout (dry) mass, thrust-to-weight, total
    impulse, burn time, length, diameter, CG, CP, drogue and main descent rates and deployment altitudes, landing
    distance, fin flutter margin and the motor. **Parts**: any component's property (`component` + `property`), with
    "fins", "nose cone", "main", "drogue" or "motor mount" understood when the design has one such part.
  - **Values as written**: "~30k ft", ">= 2 cal", "at least 100 ft/s", "under 25 lb", "Mach 1.3", "1/8 in". A number
    without a unit is not guessed (notes mix feet and metres). Default tolerances per quantity (3% apogee, 0.1 cal
    stability, 2% mass...; wider for "~"), or `tolerance` per claim.
  - Each claim returns MATCH, MISMATCH (the design's value and the difference) or UNCHECKED (why), with its `source`.
- `progress_log`: **the design's record over time.** `record` (default) keeps a dated snapshot next to the design file
  (`NAME.progress.jsonl`; designs not saved yet: the workspace's `progress/` folder): the headline numbers, every
  rule-check failure and warning and every set-up item still open. It compares with the last record, a `label`
  (milestones such as "PDR") or a date (`since`): numbers that moved beyond a quarter of their tolerance, the motor,
  problems fixed, new and worse. `digest` is the update as Markdown for a channel or a Notion page (`path` also writes
  it to a file). `preview` compares without recording; `history` lists the records. Unsaved-edit notices are left
  out (session state, not progress), and a rule item keeps its identity when its details change (a fin material).

### Reports

- `generate_report`: a Markdown design review. It has the rule checks, stability by stage, a wind-sensitivity flight-card
  table, the recovery chain, the electronics and the methods. It also writes the two stability-against-time SVG plots DTEG R10.3.2 asks
  for, and a CSV.
- `export_flight_data`: the full-resolution CSV.

### Rules and standards

- `check_requirements` checks two rule sets and adds a checklist of what to verify by hand.
  - **LC 2027 edicts:**
    - L:D ≤ 45 (25 recommended);
    - damping ratio 0.03–0.5 (0.05–0.3 recommended);
    - static margin ≥ 10% of body length up to 2× the burn time;
    - no metal rail buttons;
    - SRAD Isp ≥ 100 s;
    - dress-rehearsal pop tests.
  - **DTEG R4:**
    - launch angle;
    - rail exit ≥ 100 ft/s;
    - thrust-to-weight by year and per stage;
    - ≥ 1.5 cal ascent stability, including in 30 km/h wind;
    - over-stability;
    - air-start tilt and altitude inhibit;
    - dual-event recovery;
    - drogue descent 50–150 ft/s;
    - main deployment ≤ 1500 ft and main descent < 30 ft/s.
- `targets`: **the team's own targets and the margin to each**. The team sets limits in the standards file
  (`"targets": {"apogee": {"min": "9500 ft", "max": "10500 ft", "goal": "10000 ft"}}`) on apogee, top speed and Mach,
  peak acceleration, rail exit speed, thrust to weight on the rail, lowest and highest ascent stability (cal, above
  100 ft/s), fin flutter margin, lift-off mass, time to apogee, drogue and main descent rates, landing speed and
  distance, and flight time. It simulates and returns every number with its limits, the margin to the nearer one and
  PASS, WARN (met by less than 5%) or FAIL. The flutter margin falls back to `structures.flutterMinMargin`. These are
  design goals; `check_requirements` is the competition's rules.
- `get_standards`, `update_standards`, `load_standards`, `set_units`: the team standards and units.

### LC 2027 advanced programmes

- `pressure_vessel`: proof ≥ 1.5·MEOP, burst ≥ 2·MEOP·weld knockdown, COPV ≥ 4·MEOP, and a Barlow estimate.
- `advanced_probation`: the probation level from the GLPP volume, the static-fire Isp requirement, and AASI.

### Common to all tools

- **Units:** switchable (`metric`, `imperial`, `both`). Every input accepts units, such as `"20 ft/s"`, `"4.343 in"`,
  `"15 psi"` or `"75 ft-lbf"`; bare numbers are SI.
- **What-if tools never change the design.** `rank_motors`, `sweep`, `optimize`, `ballast`, `monte_carlo` and
  `deployment_delay_sweep` fly each variant on a copy of the rocket, in parallel across CPU cores.
- **Faster simulations:** every flight runs on OpenRocket's own engine with three per-step recomputations cached:
  the airframe's mass, CG and inertia (recomputed by OpenRocket four times per RK4 step, though it changes only at
  staging), the motors' mass and CG (cached once a motor is past its thrust curve, or before it lights) and the
  geometry check (its warnings replayed). Results are identical to the last digit on every bundled design, flight data
  and warnings alike (`FastSimTest`); about 2.9 times faster per simulation, and up to 7 times on studies. A design
  whose own OpenRocket extension hooks the mass calculation runs uncached. `-Dopenrocketmcp.noFastSim=true` turns it
  off.
- **Faster studies:** studies that only read the climb (`rank_motors`, `optimize`, `optimize_fins`, `compare_shapes`,
  `ballast`, `roll_analysis`) stop each flight once its first parachute is out, skipping the long descent. Apogee,
  stability, rail exit, Mach, flutter and ejection timing are unchanged (a test checks this).
- **Each flight is flown once:** tools that read the design's own flight (`run_simulation`, `check_requirements`,
  `flight_card`, `design_status`, the electronics tools, the report) reuse its results until the design, the motor,
  deployment, staging or launch conditions change, and the design-wind case is cached the same way. A typical
  session of a dozen such calls went from 26 s to 3 s.
- **Arguments are checked:** an argument a tool does not take is refused with the tool's list and the closest name, not
  silently ignored. Listed choices accept any case and spaces (`"Max apogee"` = `max_apogee`).
- **Physical limits:** every number is checked against what it can physically be, with a sentence naming the argument
  and the allowed range: no negative masses, areas, densities or delays; Cd above zero; latitude within ±90°; wind
  0–150 m/s; launch angle 0–60°; site temperature −100 to +100 °C; nothing beyond any rocket's scale (a 10^30 m/s wind
  or a 1 km fin is a typo or a wrong unit). Counts are whole numbers, switches are true or false. Team standards are
  checked the same way when loaded or changed, and a section replaced by a value of another shape is refused. Every
  argument is checked before the tool runs, including ones the chosen options would not use.
- **Parts:** a negative size or mass is refused, and so is a zero thickness, chord or span on fins, a zero length or
  diameter on tubes and nose cones, or a zero Cd or diameter on a parachute. Positions, offsets, sweep and motor
  overhang may be negative. When OpenRocket limits a value (a wall thicker than the tube's radius, a cant over 15°),
  the reply gives the value it kept.
- **Flights that cannot happen are flagged:** when OpenRocket stops a simulation (no lift-off, tumbling under thrust,
  a parachute out under thrust), or a flight passes Mach 3 (beyond its aerodynamics) or 150 G, `run_simulation` lists
  it first under `PROBLEMS`, `check_requirements` fails "Physically sound flight", and the flight card and review report
  open with it. OpenRocket's own warnings from the run (large angle of attack, a gap or jump in the airframe, fins too
  thick for its model, body calculations past Mach 1...) are listed the same way, as "OpenRocket warns: ...". Its
  high-speed deployment warning is left out: `recovery_analysis` judges each opening load against the team's
  standards instead, and warns when no parachute opened (a ballistic landing). Notes about the model's range rather
  than the flight (past Mach 3, OpenRocket's supersonic body caveat) are `check_requirements` "Within the models' range".
- **Errors are sentences:** a failure says what was wrong and how to fix it; an internal error says it is a bug in the
  server, gives an error ID (also in the server's log, next to the stack trace) and asks for a report.
- **Whose problem it is:** every error, and every note on a result, also comes in the result's `_meta` under
  `io.github.openrocketmcp/issues` as `{kind, severity, text, field?, id?, bug?, report?}` for clients that show them
  (the RocketMaxxing app does). `kind` is `input` (the request: a value, unit or name; `field` names the argument),
  `physics` (the rocket or its flight), `output` (a value that could not be computed, or one from beyond where the model
  is valid) or `system` (no internet, a file, a missing program, or a bug: `bug`, `id` and `report`, which has the
  versions and stack trace but no design data). The text a model reads is unchanged.
- **Component names:** an id, a full name, or a piece of a name that only one part has (`"Apex"` for
  `Apex 12" Drogue Parachute`).
- **Files people bring** (altimeter logs, mass budgets, aero tables) may be UTF-8 or Windows-1252, as Excel and many
  altimeter programs write them, with or without a byte-order mark.
- **Fair comparisons:** variants share the same wind turbulence, so differences come from the change being studied.
  Monte Carlo results repeat for a given seed, and the same design file gives the same flight in every session
  (OpenRocket does not save the turbulence seed, so every opened simulation gets a fixed one).
- **Prompts and resources:** MCP prompts `what_can_you_do`, `recovery_review`, `design_review` and `motor_selection`; resources
  `openrocket://standards`, `openrocket://rules` and `openrocket://methods`.

## Team standards

Copy [`openrocket-mcp.example.json`](../openrocket-mcp.example.json) to `openrocket-mcp.json` (in the directory the server
runs from, or point `OPENROCKET_MCP_STANDARDS` at it) and commit it, so everyone on the team gets the same answers:

- `units`, `ruleset` (`launch-canada-r4`, `none`, or a path to your own rules JSON), `competitionYear`
- `launchSite`: altitude above sea level (**set this** — deployment air density depends on it), lat/lon, rail length,
  launch angle, design wind
- `structures`: required flutter margin and fin-material stiffness and strength.
  - Typical values are built in for G10, carbon, aluminium, steel, titanium, plywood and polycarbonate.
  - Also built in: balsa and basswood (*Wood Handbook*), and 3-D printed PLA, PETG and ABS.
  - Put your laminate's or print's measured value here: wood and printed parts vary a lot.
- `recovery`: Cx, opening-load method (`infinite_mass` / `finite_mass` / `max`), canopy fill constant, safety factors
  for shear pins and ejection force, backup-charge factor, packing factor and measured packing factors, shock cord
  cross-section, **shear pin ratings** (e.g. `"4-40 nylon": {"strength": "140 N"}`)
- `electronics`: the team's `sensors` (type, range, noise, resolution), `altimeter` choices (backup delay and offset,
  barometer Mach limit, lockout margin), `power` (battery circuits, pad wait, search time, derating, e-match) and
  `radio` (frequency, power, antenna gains, receiver sensitivity, losses, wanted margin, antenna heights). The defaults
  are typical student parts: replace them with yours.
- `suggestions`: `false` stops results from offering features the team has not used yet.

Every value is checked when the standards are loaded or changed: a section must keep the shape of the defaults, and
quantities must be physically possible (a negative modulus or a zero pin strength is refused with the reason).

Claude can change them with `update_standards` (and save them with `saveTo`).

## Methods (and their limits)

See `openrocket://methods` for equations and sources. In short:

- **Flight**: OpenRocket 24.12 6-DOF simulation, Barrowman aerodynamics (with the per-step caching above, which changes
  no number). For airframes with diameter changes, Launch Canada expects RASAero CP/CD overrides — the checker flags
  this.
- **Deployment conditions** come from the simulation (airspeed = Mach × speed of sound, so horizontal velocity and wind
  are included), not from vertical-only hand estimates.
- **Opening load**: Knacke (NWC TP 6575) infinite-mass `Cx·q·CdA`, plus a finite-mass inflation integration with fill time
  `n·D0/v`; Knacke's mass ratio indicates which applies. The design load is never below steady-descent drag.
- **Black powder**: ideal-gas method, R = 266 in·lbf/(lbm·°R), T = 3307 °R (≈ 0.006·D²·L g at 15 psi).
- **Engine prediction** (`simulate_engine`): a lumped model after Sutton, *Rocket Propulsion Elements*. At each 2 ms
  step the chamber pressure is the one at which the throat passes what the propellant feeds in, `Pc = flow · c* / At`,
  and thrust is `Cf · Pc · At` for a one-dimensional isentropic nozzle of constant gamma (momentum term times the
  nozzle efficiency, plus `(pe − pa) · Ae`). Solid: `r = a·Pc^n` on the burning area of BATES grains, so
  `Pc = (ρ·a·c*·Ab/At)^(1/(1−n))`. Hybrid: injector flow `Cd·A·√(2ρ(Ptank − Pc))` balanced against the throat by
  bisection, fuel from `r = a·Gox^n` on a port that widens, c* read at the mixture ratio of the moment. Liquid: fixed
  mixture ratio; blowdown `Pc = Pc0·(u/(u + x(1−u)))^k`. Checked against isentropic tables (NACA 1135) and against
  burns with closed-form answers (port growth at constant oxidizer flow, the Kn relation, mass conservation). **Not
  modelled**: ignition and tail-off, erosive burning, throat erosion, two-phase flow of self-pressurising oxidizers,
  combustion dynamics, heat loss beyond the c* efficiency. It is a design estimate: fire the engine and use the log.
- **Fin flutter**: NACA TN 4197 screening estimate with K = 2.674 (the widely copied 1.337 form overestimates flutter
  speed by √2, corrected in Apogee Peak of Flight #615), evaluated at every point of the flight. Solid plate fins only;
  composites need an effective shear modulus, and a stiffness test or FEA before relying on it.
- **Fin FEA**: CalculiX S8R shells (8-node, reduced integration), root fully clamped, uniform pressure equal to the fin's
  normal force from OpenRocket's Barrowman fin model at the design angle of attack (the fins square to the flow carry it,
  2/n of the set). Checked against cantilever plate theory (tip deflection, root stress, first frequency within 1-3%).
  A homogeneous plate with in-plane engineering constants, not a ply-by-ply laminate model; the real pressure peaks near
  the leading edge, and root fillets or tabs make real fins more flexible than a clamp.
- **Fin optimization** flies every candidate with OpenRocket's own drag model even when an aero table is imported (the
  table describes the old fins); re-run CFD on the chosen shape.
- **CFD hand-off**: STL in the rocket frame (x from the nose tip, freestream in +x), reference area = maximum body
  cross-section, as OpenRocket and RASAero use. Flat fins with square edges; no rail buttons or fillets.
- **Drag breakdown**: OpenRocket reports drag per fin (and per rail button); `aero_analysis` counts every copy and lists
  the whole-rocket corrections (body friction form factor, fin thickness) that belong to no single part.
- **Shape studies** rank options with OpenRocket's empirical drag model; differences under ~2% are within its
  uncertainty.
- **Sections** assume bays open at their forward end (name the joints otherwise).
- **Loads** are rigid-body, quasi-static, with inertial relief. They size couplers and fasteners but do not replace
  buckling checks or tests.
- **Weather**: Open-Meteo forecast (set `OPENROCKET_MCP_WEATHER_URL` for a mirror); pressure-level winds placed at their
  geopotential heights above the site; turbulence from the gust factor. Forecasts carry uncertainty: re-check on the day.
- **Heating** temperatures are adiabatic-wall upper bounds (surfaces lag them); **roll** comes from OpenRocket's
  fin-cant model and ignores other asymmetries, so the alignment tolerance is a build target, not a margin.
- **Imported aero tables** apply until the first stage separation (a RASAero table describes one stack); the flight-log
  drag fit assumes the motor, mass and launch conditions of the simulation match the day.
- **Winds aloft**: with a multi-level profile, tools that set "the" wind (sweeps, the 30 km/h design-wind check, Monte
  Carlo) scale and rotate the whole profile from its lowest level, keeping its shape.
- **OpenRocket quirks handled**: OpenRocket 24.12's wind getters silently switch a simulation back to the single-wind
  model, and its first mass calculations after loading a design disagree by ~1 mm of CG; both are worked around (and
  covered by tests) so profiles persist and static margins are stable.
- **Mass overrides**: if a section's mass is overridden for its subcomponents (a weighed section), OpenRocket ignores
  mass added inside it. The tools warn about this, and `ballast` adds its mass to the override.
- **3-D views and animation** use a built-in software renderer (depth buffer, 2x2 supersampling), so they need no
  graphics card or extra install. Geometry and masses are OpenRocket's; rail buttons and lugs are not drawn. In the
  animation, position, attitude, roll, thrust and every number shown come from the simulation. The hanging pose under
  the canopy, the canopy's inflation and the smoke are illustrative.
- **Rail buttons**: the rail is left when the aft button passes its top. Speeds along the rail come from the
  simulation. Tip-off is a rigid rocket pivoting on the aft button, pushed by gravity across the tilted rail and a
  worst-direction crosswind on the CP (Barrowman CNα at low Mach); slop = button play / spacing. Friction, rail flex
  and thrust misalignment are left out, so the numbers rank layouts rather than predict the departure angle.
- **Design library**: the predicted apogee is the design file's own saved simulation, in the conditions set in that
  file, not the launch day's weather. `compare_flight` re-flies a log in the day's conditions.
- **Sensor data**: specific force from OpenRocket's thrust, drag (axial) and normal-force coefficient (lateral) over
  mass. Body rates and ambient air are the simulation's. A test checks the accelerometer against the trajectory
  (dvz/dt = a_x cos(tilt) - g within 1 m/s²). Not modelled: vibration, deployment shocks (OpenRocket opens canopies
  instantly, so opening peaks are upper bounds), bias and drift, bay pressure lag and port errors near Mach 1.
- **Power budget**: constant average current over the whole day (measure it armed and transmitting); capacity derated
  for cold and age; the e-match current is Ohm's law with the battery's internal resistance, which falls as a battery
  cools or ages. Not modelled: capacity loss at high current, temperature, self-discharge.
- **Radio link**: free-space loss 20 log10(4π d f / c) in the air; on the ground the plane-earth model (40 dB per
  decade beyond the two-ray crossover 4π h1 h2 / λ), which is pessimistic over open fields and optimistic in forest
  or hills. Antenna patterns, polarisation and the rocket's orientation are in `lossesDb`; keep a 10 dB margin.
- **Parachute sizes**: steady rate v = sqrt(2 m g / (rho (n Cd A + CdA_other))). CdA_other is calibrated from the
  simulated rate of the current parachute, so it includes everything OpenRocket models besides that canopy.
- **Product pages** are read from their structured product data (JSON-LD) and visible text. Shops word their pages
  differently, so check what was found against the vendor's sheet. A Cd quoted on the projected area (some toroidal
  canopies) must be used with the projected area.
- **Cd reference area**: OpenRocket uses the nominal canopy area. Vendor Cd values quoted on projected area (e.g. 2.2)
  must be paired with projected area.

Outputs are engineering estimates for design iteration. They do not replace ground tests, flight tests, mentors or the
RSO.

## Plots, images, progress and cancellation

- **Plots:** `run_simulation` and `monte_carlo` take `plotPath` (SVG).
  - The flight profile shows altitude against time per stage, with burnout, separation, apogee and deployments marked.
  - The landing map shows every landing and the 2-sigma ellipse per stage, on equal-scale axes with the pad marked.

  `generate_report` includes the flight profile. Plots follow the team's unit setting (imperial for an imperial team,
  else metric) and light or dark themes.
- **Images in the chat:** tools that draw also return the picture as a PNG in the result, so clients that show images
  (Claude Desktop, claude.ai) display it in the chat. These are `draw_rocket`, `render_3d`, `animate_flight` (its
  key-moment stills) and `export_geometry`, plus
  `run_simulation`, `monte_carlo`, `compare_flight`, `optimize_fins` and `fin_fea` when given a plot path. Set
  `OPENROCKET_MCP_INLINE_IMAGES=0` to send text only.
- **Progress:** long tools send MCP progress notifications (simulations flown out of the total) when the client passes a
  `progressToken`. Over stdio they are notifications; on the team server they are server-sent events.
- **Cancel:** `notifications/cancelled` stops a call at its next batch of simulations, and no result is sent for it.
- **Ordering:** over stdio, requests are handled one at a time in the order they arrive; `ping` and cancellation are
  answered straight away. On the team server, calls on the same design take turns and different designs run in
  parallel.
- **Video:** `animate_flight` writes the GIF at `gifWidth` (720 px by default; GIFs grow quickly with size). The MP4 is
  H.264 at `width` (1280 px by default), made by ffmpeg when it is on the PATH (or set `OPENROCKET_MCP_FFMPEG`), else
  encoded in Java with JCodec (larger files, about 0.6 MB per second of video at 1280 px, and a little slower). Frames
  render in parallel and stream to both encoders; a 25 s animation takes about half a minute.
- **Google Earth:** `monte_carlo` `kmlPath` writes the pad, every landing and each stage's 2-sigma ellipse, placed at
  the team's launch site (`launchSite.latitude` / `longitude`) or at `siteLatitude` / `siteLongitude`.

## Development

```sh
./gradlew test                  # unit and integration tests (see below)
./gradlew installDist           # build/install/openrocket-mcp/bin/openrocket-mcp[.bat]
python3 scripts/benchmark.py    # scenario benchmark: realistic team requests, pass/fail + timings + output size
python3 scripts/make_examples.py  # rebuilds docs/EXAMPLES.md, its plots and the demo designs from real runs (also in CI)
node scripts/view_shots.mjs docs/examples/maple-10k-view.html docs/examples  # the view in headless Chromium + screenshots
```

`./gradlew test` runs about 375 tests: calculators against the team's worked examples and hand calculations,
property tests (scaling laws, inverses), the protocol, standards, SVG output, OpenRocket-backed checks, the 3-D renderer
and flight animation (single-stage, two-stage and side boosters), the caching simulation engine against OpenRocket's own
on every bundled design, the interactive view's data and page, and end-to-end MCP calls.

`scripts/benchmark.py` drives a fresh server over stdio through realistic team requests and checks each answer against
engineering expectations. It runs in CI. The scenarios:

- design a 10,000 ft rocket from scratch and make it pass Launch Canada;
- size recovery and check the loads; a custom liquid engine, pressure vessels and probation;
- a two-stage rocket: the air-start rules, each stage's landing and chute, the stage-bracketed 3-D view and the
  animation's stage captions;
- dispersion and a design-review report; fin flutter, ballast and vehicle-uncertainty dispersion;
- aero analysis, winds aloft, RASAero import and flight-log calibration;
- shape study, recovery sections and structural loads; fin optimization, CFD export and fin FEA;
- launch-day weather and flight card; a design-review diff;
- an avionics bay; pictures in the chat, progress and cancel, status, undo and redo, the mass budget and the KML map.

The server speaks MCP over stdio (JSON-RPC 2.0, newline-delimited); stdout is reserved for the protocol, logs go to
stderr. See [`dev/SPEC.md`](dev/SPEC.md) for the design and roadmap.

## Team server (HTTP)

`openrocket-mcp --http` serves MCP over HTTP (Streamable HTTP transport, JSON responses, batches accepted, no
server-initiated stream so `GET /mcp` is 405) for claude.ai custom connectors, Claude Desktop and Claude Code.

| Option | Default | |
|---|---|---|
| `--port` | 8765 (`$PORT`) | |
| `--host` | 127.0.0.1 | `0.0.0.0` to accept other machines |
| `--workspace` | current folder | shared folder; every tool path is resolved inside it and cannot leave it (`..`, absolute paths and symbolic links out are refused) |
| `--token` | `$OPENROCKET_MCP_TOKEN`, else generated once into `WORKSPACE/.openrocket-mcp-token` | at least 16 characters; required unless `--no-auth` on a loopback address |
| `--public-url` | `http://HOST:PORT` | the address people use, for the links the server prints and `list_files` returns |
| `--allow-origin` | none | extra browser origins; others are refused (DNS-rebinding protection); requests without `Origin` (server-to-server) are allowed. These origins also get CORS headers (preflight `OPTIONS` answered), so a browser MCP client on them (the MCP Inspector, an MCP Apps host) can call the server; loopback pages get none |

Endpoints: `POST /mcp` with `Authorization: Bearer TOKEN`, or `POST /mcp/TOKEN` for clients that only take a URL;
`GET /files/TOKEN/` lists the workspace with download links and a drag-and-drop upload (`PUT /files/TOKEN/path`,
200 MB limit, dot-files hidden and refused); `GET /health`. Tool calls on the same design run one at a time; calls on
different designs run in parallel. Paths in results are shown relative to the workspace. Open designs, units and
standards are shared by everyone connected. `Dockerfile` builds a container that serves `/workspace` (git included,
so `compare_designs` can read earlier revisions of a checked-out repository).

## Claude Desktop extension

`./gradlew mcpb` builds `build/distributions/openrocket-mcp-VERSION-PLATFORM-ARCH.mcpb`: the manifest (generated from
the registered tools by `io.github.openrocketmcp.Manifest`), the server and OpenRocket jars, and a `jlink` Java runtime
for the build machine's platform (~80 MB). Claude Desktop asks for the rocket folder (`OPENROCKET_MCP_WORKSPACE`:
relative paths, reports and `openrocket-mcp.json` live there) and optionally a standards file. CI builds and
smoke-tests it (`scripts/test_mcpb.py` unpacks it and starts it exactly as the manifest says) on Linux, Windows and
macOS arm64 / x64, and attaches the bundles to GitHub releases for `v*` tags.

## Claude integration

- MCP prompts: `what_can_you_do` (a tour fitted to the team's design), `meeting_followup` (notes checked against the
  design, follow-up drafted), `weekly_update` (progress record and update), `recovery_review`, `design_review`,
  `motor_selection`; resources: `openrocket://standards`,
  `openrocket://rules`, `openrocket://methods`.
- Claude Code skills: `.claude/skills/rocket-design-review` (the review workflow) and
  `.claude/skills/team-chief-engineer` (project manager and chief engineer: notes, action items, weekly update); the
  same skills for Codex and other agents in `.agents/skills/` (kept identical by `SkillsTest`).
- Codex: stdio (`command`) or the team server (`url` + `bearer_token_env_var`) in `~/.codex/config.toml`; raise
  `tool_timeout_sec` (default 60 s) for dispersion and motor ranking. Codex offers the tools to its model under the
  `mcp__openrocket` namespace with the schemas unchanged. `scripts/test_codex.py` runs the real Codex CLI against the
  server with a local stand-in for the model (in CI, Codex version pinned).
- Team standards file location: `openrocket-mcp.json` in the workspace (the directory the server runs from, or
  `OPENROCKET_MCP_WORKSPACE` / `--workspace`), or the path in the `OPENROCKET_MCP_STANDARDS` environment variable.
- Imported aero tables are kept next to the design as `NAME.aero.json` (SI, readable, diffable) when it is saved, and
  loaded again with it.

## Prior art

[Poyraxx/openrocket-mcp-support](https://github.com/Poyraxx/openrocket-mcp-support) (an OpenRocket fork with an MCP
bridge), [openrocket/orhelper](https://github.com/openrocket/orhelper).

