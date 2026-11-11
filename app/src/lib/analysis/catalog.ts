// The engine's analyses as the Analysis page lists them. `main`: the inputs shown up front (the rest fold away);
// `defaults`: starting values, given the design's file-safe name.

/** A plot of a list in the result: x column (default the first), columns on the left and right axes. */
export interface PlotSpec {
  list: string;
  x?: string;
  left: string[];
  right?: string[];
}

export interface Analysis {
  tool: string;
  label: string;
  blurb?: string;
  main: string[];
  defaults?: (name: string) => Record<string, string>;
  plot?: PlotSpec;
  /** Inputs of which at least one is needed (the engine checks the combination). */
  oneOf?: string[];
  /**
   * Inputs that belong to some choices of another input only (an engine's grain for a solid, its tanks for a liquid):
   * the input they depend on, and for each such input the choices it is shown and sent for.
   */
  when?: { key: string; fields: Record<string, string[]> };
  /** Inputs that name a file to read, with the extensions to offer. */
  files?: Record<string, string>;
}

export interface Group {
  title: string;
  items: Analysis[];
}

// simulate_engine: what each engine type is described by, in the order the form asks for it.
const ENGINE_SOLID = ["grainCount", "grainOuterDiameter", "grainCoreDiameter", "grainLength", "propellantDensity", "burnRateCoefficient", "burnRateExponent", "burnRateUnits", "inhibitedEnds"];
const ENGINE_HYBRID = ["oxidizerMass", "tankPressure", "tankPressureEnd", "injectorHoleDiameter", "injectorHoleCount", "dischargeCoefficient", "oxidizerDensity", "oxidizerFlow", "portDiameter", "portCount", "grainOuterDiameter", "grainLength", "fuelDensity", "regressionCoefficient", "regressionExponent"];
const ENGINE_LIQUID = ["fuelMass", "mixtureRatio", "chamberPressure", "feed", "ullageFraction", "polytropicExponent"];
const ENGINE_WHEN: Record<string, string[]> = {};
for (const [type, fields] of [["solid", ENGINE_SOLID], ["hybrid", ENGINE_HYBRID], ["liquid", ENGINE_LIQUID]] as const) {
  for (const f of fields) (ENGINE_WHEN[f] ??= []).push(type);
}
// Shared by two types.
ENGINE_WHEN.grainOuterDiameter = ["solid", "hybrid"];
ENGINE_WHEN.grainLength = ["solid", "hybrid"];
ENGINE_WHEN.oxidizerMass = ["hybrid", "liquid"];
ENGINE_WHEN.cstarTable = ["hybrid", "liquid"];
ENGINE_WHEN.oxidizerCgFromTop = ["hybrid", "liquid"];

export const CATALOG: Group[] = [
  {
    title: "Checks",
    items: [
      { tool: "check_requirements", label: "Rule check", blurb: "Simulates the flight and checks it against the competition rules and your team standards.", main: ["simulation", "includeWindCase"] },
      { tool: "design_status", label: "Readiness", blurb: "What is done, what is missing, and what to do next.", main: ["configuration"] },
      { tool: "verify_claims", label: "Check our notes", blurb: "Checks each number your notes or messages state against the design.", main: ["claims", "simulation"] },
      { tool: "advanced_probation", label: "Probation level (LC)", blurb: "Launch Canada advanced probation level and AASI for hybrid and liquid entries.", main: ["glppVolume", "staticFireIsp", "totalImpulse", "propellantMass", "targetAltitude", "actualAltitude", "predictedAltitude"] },
    ],
  },
  {
    title: "Stability and drag",
    items: [
      { tool: "aero_analysis", label: "Stability and drag vs Mach", plot: { list: "vsMach", x: "mach", left: ["cd"], right: ["marginLaunch", "marginBurnout"] }, blurb: "Drag coefficient, CP and static margin across Mach numbers, with the drag breakdown.", main: ["stage", "machs", "maxMach"] },
      { tool: "ballast", label: "Nose ballast", blurb: "Nose ballast needed to reach a stability target, and what it costs in apogee.", main: ["targetStability", "component", "position"] },
      { tool: "roll_analysis", label: "Roll from fin cant", blurb: "Roll rate from fin cant or misalignment.", main: ["finSet", "cantAngles", "maxRollRate"] },
      { tool: "compare_shapes", label: "Nose and fin shapes", blurb: "Flies other nose cone shapes and lengths, and fin shapes, against the current design.", main: ["noseCone", "noseLengths", "fins"] },
    ],
  },
  {
    title: "Recovery",
    items: [
      { tool: "recovery_analysis", label: "Recovery loads", blurb: "Each deployment from the simulated flight: airspeed, opening load, descent rate, shear pins.", main: ["simulation", "pinType", "pinStrength"] },
      { tool: "parachute_sizes", label: "Parachute size chart", blurb: "Descent rate against canopy diameter for a few drag coefficients.", main: ["device", "cds", "targetDescentRate", "apply"] },
      { tool: "size_parachute", label: "Size a parachute", blurb: "Canopy diameter for a target descent rate.", main: ["targetDescentRate", "device", "cd", "count"] },
      { tool: "deployment_delay_sweep", label: "Late deployment", plot: { list: "results", x: "delay", left: ["designLoad", "openingLoadFiniteMass"], right: ["altitudeAGL"] }, blurb: "How the opening load grows if a device deploys late.", main: ["device", "delays", "pinType", "pinCount"] },
      { tool: "recovery_sections", label: "Recovery sections", blurb: "The sections that come down separately, with their masses.", main: ["joints"] },
      { tool: "shear_pins", label: "Shear pins", blurb: "Number of shear pins to hold a load, with a safety factor.", main: ["holdForce", "pinType", "pinStrength", "safetyFactor"] },
      { tool: "ejection_charge", label: "Ejection charge", blurb: "Black powder for a bay, from its volume and the pins to break.", main: ["bayTube", "bayDiameter", "bayLength", "bayVolume", "pinType", "pinCount", "pressure"], oneOf: ["bayTube", "bayDiameter", "bayVolume"] },
      { tool: "opening_shock", label: "Opening shock", blurb: "Opening load of a canopy at a given speed and mass.", main: ["mass", "velocity", "cd", "diameter"] },
      { tool: "recovery_bay_fit", label: "Recovery bay fit", blurb: "Whether the parachutes, cords and hardware fit in the bay, and the length needed.", main: ["items", "bayComponent", "availableLength", "packingFactor"], oneOf: ["bayComponent", "bayTube", "bayDiameter", "availableVolume"] },
      { tool: "search_parachutes", label: "Parachute catalogue", blurb: "Parachutes in OpenRocket's parts database by diameter and maker, with Cd and packed size.", main: ["minDiameter", "maxDiameter", "manufacturer", "limit"] },
      { tool: "descent_energy", label: "Landing energy", blurb: "Kinetic energy of each section at touchdown.", main: ["sections", "descentRate", "energyLimit"] },
    ],
  },
  {
    title: "Structures",
    items: [
      { tool: "fin_flutter", label: "Fin flutter", blurb: "Fin flutter speed against airspeed along the flight.", main: ["finSet", "shearModulus", "thickness"] },
      { tool: "fin_fea", label: "Fin stress (FEA)", blurb: "Stress, tip deflection and vibration of one fin under the worst load of the simulated flight.", main: ["finSet", "thickness", "safetyFactor", "gustSpeed", "run"], defaults: (n) => ({ plotPath: `plots/${n}-fin-stress.svg` }) },
      { tool: "structural_loads", label: "Airframe loads", blurb: "Axial and bending loads at each airframe joint, at max q with a gust.", main: ["gustSpeed", "safetyFactor", "allowableStress"] },
      { tool: "aero_heating", label: "Aerodynamic heating", blurb: "Skin temperature screen for the nose and fins.", main: ["noseTipRadius"] },
      { tool: "compare_materials", label: "Material trade", blurb: "Flies the fins or airframe in other materials and thicknesses.", main: ["parts", "materials", "thicknesses"] },
      { tool: "pressure_vessel", label: "Pressure vessel", blurb: "Burst and proof margins of a tank or motor case.", main: ["meop", "burstPressure", "outerDiameter", "wallThickness", "ultimateStrength"] },
    ],
  },
  {
    title: "Launch and avionics",
    items: [
      { tool: "add_avionics_bay", label: "Avionics bay", blurb: "Builds the electronics bay between two airframe tubes.", main: ["upperTube", "lowerTube", "altimeters", "tracker", "couplerLength", "switchBand", "packRecovery"] },
      { tool: "weather_forecast", label: "Launch-day forecast", blurb: "Wind by altitude, temperature and pressure at the site for the launch hour.", main: ["latitude", "longitude", "time", "apply", "forecastJson"] },
      { tool: "rail_buttons", label: "Rail buttons", blurb: "Rail button positions, rail exit speed and tip-off.", main: ["forward", "aft", "crosswind", "apply"] },
      { tool: "flight_card", label: "Flight card", blurb: "Writes the launch-day flight card.", main: ["path"], defaults: (n) => ({ path: `${n}-flight-card.md` }) },
      { tool: "altimeter_settings", label: "Altimeter settings", blurb: "Settings for the primary and backup altimeters: deployment, Mach lockout, port size.", main: ["bay", "bayVolume"] },
      { tool: "sensor_check", label: "Sensor ranges", blurb: "Sensor ranges against what the flight reaches.", main: ["sensors", "simulation"] },
      { tool: "sensor_data", label: "Flight software test data", blurb: "Writes what the flight computer's sensors would read in flight, to test its software.", main: ["path", "rate", "gpsRate", "sensors"], defaults: (n) => ({ path: `${n}-sensors.csv` }) },
      { tool: "radio_link", label: "Radio link", blurb: "Tracker radio link margin in flight and after landing.", main: ["frequencyMhz", "txPowerDbm", "rxSensitivityDbm", "groundStationHeight"] },
      { tool: "power_budget", label: "Power budget", blurb: "Battery life of each avionics circuit through the pad wait and the flight.", main: ["circuits", "padWait", "recoveryTime"] },
      { tool: "wind_profile", label: "Winds aloft", blurb: "Sets wind by altitude for the simulations, from levels you enter or a power law.", main: ["mode", "levels", "groundSpeed", "direction", "exponent"] },
    ],
  },
  {
    title: "Studies",
    items: [
      { tool: "monte_carlo", label: "Monte Carlo dispersion", defaults: (n) => ({ plotPath: `plots/${n}-landing.svg` }), blurb: "Many flights with varied wind, launch angle and more: landing spread and worst cases.", main: ["runs", "windSpeed", "windSpeedSd", "windDirection"] },
      { tool: "simulate_engine", label: "Engine prediction", blurb: "A solid, hybrid or liquid engine's thrust, chamber pressure and burn time from its design, before it is fired; makes it a motor to fly.", main: [...new Set(["type", "throatDiameter", "exitDiameter", "cstar", "cstarTable", "gamma", ...ENGINE_SOLID, ...ENGINE_HYBRID, ...ENGINE_LIQUID, "designation", "manufacturer", "diameter", "length", "dryMass"])], when: { key: "type", fields: ENGINE_WHEN }, defaults: (n) => ({ type: "hybrid", plotPath: `plots/${n}-engine.svg` }) },
      { tool: "rank_motors", label: "Rank motors", blurb: "Flies each motor that fits the mount and ranks them.", main: ["mount", "objective", "targetApogee", "minClass", "maxClass", "certLevel"] },
      { tool: "sweep", label: "Parameter sweep", plot: { list: "results", left: ["apogee"], right: ["minStability", "maxStability"] }, blurb: "Flies the design with one property set to each value in a list.", main: ["component", "parameter", "values"] },
      { tool: "optimize_fins", label: "Fin planform optimizer", blurb: "Finds the fin shape and thickness that best meet the goal within the rules.", main: ["finSet", "objective", "targetApogee", "thicknesses", "maxSpan", "apply"], defaults: (n) => ({ plotPath: `plots/${n}-fins.svg` }) },
      { tool: "optimize", label: "Optimizer", blurb: "Changes the parts you choose, within limits, to reach a goal while meeting the rules.", main: ["variables", "objective", "targetApogee", "meetRules", "apply"] },
    ],
  },
  {
    title: "Compare and import",
    items: [
      { tool: "add_from_link", label: "Part from a shop link", blurb: "Puts a product into the design from its web page.", main: ["url", "pageText", "kind", "parent", "component", "apply"] },
      { tool: "compare_designs", label: "Compare designs", blurb: "Differences in parts and flight against another open design or a file.", main: ["baselineDesignId", "baselinePath", "revision", "sameConditions"], oneOf: ["baselineDesignId", "baselinePath", "revision"], files: { baselinePath: "ork,rkt" } },
      { tool: "progress_log", label: "Progress log", blurb: "Records where the design stands, and what changed since a milestone or date.", main: ["mode", "label", "note", "since"] },
      { tool: "history", label: "Edit history", blurb: "The edits made in this session that can be undone.", main: [] },
      { tool: "import_aero_table", label: "RASAero aero table", blurb: "Uses CP and drag from a RASAero export (per stage) instead of OpenRocket's.", main: ["mode", "stage", "path", "cpUnit", "useDrag"], files: { path: "csv,txt" } },
      { tool: "compare_flight", label: "Flight log vs simulation", blurb: "Compares an altimeter log with the simulated flight.", main: ["path", "altitudeUnit", "simulation"], oneOf: ["path", "csv"], files: { path: "csv,txt" }, defaults: (n) => ({ plotPath: `plots/${n}-flight-vs-log.svg` }) },
    ],
  },
  {
    title: "Files",
    items: [
      { tool: "generate_report", label: "Design review report", blurb: "Writes a design review report with plots.", main: ["outputDir", "title"], defaults: (n) => ({ outputDir: `reports/${n}` }) },
      { tool: "export_flight_data", label: "Flight data (CSV)", blurb: "Writes the flight data of a simulation as CSV.", main: ["path", "variables"], defaults: (n) => ({ path: `${n}-flight.csv` }) },
      { tool: "export_geometry", label: "Geometry for CAD and CFD", blurb: "Writes the outer shape and flight conditions for CAD and CFD.", main: ["outDir", "units"], defaults: (n) => ({ outDir: `geometry/${n}` }) },
      { tool: "mass_budget", label: "Mass budget sheet", blurb: "Writes a mass budget sheet to fill in with weighed masses.", main: ["targetLaunchMass", "contingency"] },
    ],
  },
];

/** Launch-condition overrides most flight tools take; they start folded. */
export const OVERRIDES = ["windSpeed", "windDirection", "windTurbulence", "railLength", "launchAngle", "launchDirection",
  "launchIntoWind", "launchSiteAltitude", "latitude", "longitude", "temperature"];

/** Inputs that name a part, and which parts fit (by component type; "mount": any motor mount). */
export const PART_INPUTS: Record<string, string[] | "any" | "mount"> = {
  finSet: ["TrapezoidFinSet", "EllipticalFinSet", "FreeformFinSet", "TubeFinSet"],
  device: ["Parachute", "Streamer"],
  mount: "mount",
  noseCone: ["NoseCone"],
  bayTube: ["BodyTube", "TubeCoupler"],
  upperTube: ["BodyTube"],
  lowerTube: ["BodyTube"],
  bayComponent: ["BodyTube", "TubeCoupler", "NoseCone"],
  component: "any",
  parent: "any",
};

/** Text inputs that take a pasted page or file: a full-width box. */
export const LONG_INPUTS = ["pageText", "forecastJson"];
