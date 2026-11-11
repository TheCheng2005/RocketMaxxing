// How to use RocketMaxxing, in one place: the Guide, the Analysis page's Start here and the Standards page read it.

import type { Page } from "../state.svelte";
import { keys } from "../keys";

/** A first design session, in order. */
export const STEPS: { title: string; text: string; page?: Page; analysis?: string }[] = [
  { title: "Open a design", text: "Open your .ork (OpenRocket) or .rkt (RockSim) file, or start from an example on the Start page.", page: "home" },
  { title: "Set the team standards once", text: "Pick the competition rule set and enter your launch site, shear pins and fin materials. Every check uses them, so do this before trusting a number.", page: "standards" },
  { title: "Check the parts", text: "On Design, click parts in the list or the 3-D view to edit them. Weigh what you can and type the real masses: a mass override beats an estimate.", page: "design" },
  { title: "Choose a motor", text: "On Motors, pick the motor for each configuration, its delay, and when each parachute deploys.", page: "motors" },
  { title: "Read the rule check", text: "The rule check under the 3-D view runs after every edit. Fix FAILs first; a WARN needs a look.", page: "design" },
  { title: "Run the analyses for a review", text: "Analysis › Start here lists them by the question each answers, with a design-review order.", page: "analysis", analysis: "start" },
  { title: "Save and share", text: `Save with ${keys("S")}. The design review report (Analysis › Files) writes report.md with plots for the team.`, analysis: "generate_report" },
];

/** What each page is for. */
export const PAGE_HELP: { page: Page; title: string; text: string }[] = [
  { page: "home", title: "Start", text: "Open a file, a recent design or an example." },
  { page: "design", title: "Design", text: "The parts list, the 3-D view (Side, Back, 3D, cut-away, exploded), the selected part's properties, the summary (stability, apogee, rail exit) and the live rule check. Add parts from Add part; select one first and the new part goes into it. Drag parts in the list to move them into another part; drag the selected part along the rocket in the view to slide it, lined up with its neighbours." },
  { page: "motors", title: "Motors", text: "Flight configurations: the motor in each mount, ejection delays, when each parachute deploys and when stages separate." },
  { page: "flights", title: "Flights", text: "Saved simulations with their launch conditions (wind, rail, site), and plots of any flight variable." },
  { page: "targets", title: "Targets", text: "Your team's own goals (apogee window, rail exit, stability…) with live margins next to the competition's rules." },
  { page: "standards", title: "Standards", text: "The numbers every check uses: rule set, launch site, recovery factors and pin ratings, material properties, electronics." },
  { page: "analysis", title: "Analysis", text: "51 analyses: rules, stability, recovery, structures, launch day, electronics, studies, files. Each has an ⓘ saying what it needs and gives." },
  { page: "flightdata", title: "Flight data", text: "Altimeter logs compared with the simulation, and your past rockets with predicted against measured apogee." },
];

/** The analyses by the question they answer. */
export const QUESTIONS: { q: string; why: string; tools: [string, string][] }[] = [
  {
    q: "Is it ready, and does it pass the rules?",
    why: "Start every review here.",
    tools: [
      ["design_status", "what is set up, what is missing, what to do next"],
      ["check_requirements", "every rule PASS / WARN / FAIL with its reference, also in the maximum wind"],
      ["verify_claims", "do the numbers in your notes and slides match the design?"],
    ],
  },
  {
    q: "Is it stable, and where does the drag come from?",
    why: "Stability margin through the flight and through Mach.",
    tools: [
      ["aero_analysis", "Cd, CP and margin from subsonic to supersonic, drag by part"],
      ["ballast", "nose weight needed for a stability target, and its apogee cost"],
      ["compare_shapes", "every nose and fin edge profile flown against yours"],
      ["roll_analysis", "roll rate from fin misalignment, and the tolerance to build to"],
    ],
  },
  {
    q: "Will recovery work?",
    why: "Deployment loads, parachute sizes, pins and charges.",
    tools: [
      ["recovery_analysis", "each deployment's speed, opening load, pins needed, descent rate"],
      ["parachute_sizes", "descent rate against canopy size for a few Cd values"],
      ["shear_pins", "pins to hold a joint shut"],
      ["ejection_charge", "black powder for each bay"],
      ["recovery_bay_fit", "do the chutes and cords fit?"],
      ["deployment_delay_sweep", "what if a charge fires late?"],
      ["descent_energy", "landing energy of each section"],
    ],
  },
  {
    q: "Will it hold together?",
    why: "Fins, airframe, heat and tanks.",
    tools: [
      ["fin_flutter", "fin flutter speed against the speed flown"],
      ["fin_fea", "stress, deflection and vibration of one fin (needs CalculiX for the full result)"],
      ["structural_loads", "axial and bending loads at every joint"],
      ["compare_materials", "fins or airframe in other materials and thicknesses"],
      ["aero_heating", "nose and fin temperatures, from about Mach 2"],
      ["pressure_vessel", "tank and chamber burst and proof margins"],
    ],
  },
  {
    q: "Which motor, and can the design do better?",
    why: "Trade studies that fly many versions of the rocket.",
    tools: [
      ["simulate_engine", "your own engine's thrust from its design, before a firing"],
      ["rank_motors", "every motor that fits, flown and ranked"],
      ["optimize_fins", "the best fin shape within the rules"],
      ["sweep", "vary one thing and see apogee and stability"],
      ["optimize", "let it find values for a goal"],
      ["monte_carlo", "landing area and spread with random wind and launch errors"],
    ],
  },
  {
    q: "Launch day and electronics",
    why: "The day's weather, the rail, altimeters and radios.",
    tools: [
      ["weather_forecast", "the forecast winds and air, applied to the flight"],
      ["rail_buttons", "where the buttons go, real rail exit speed"],
      ["altimeter_settings", "primary and backup settings, Mach lockout"],
      ["flight_card", "the one-page card for the range"],
      ["sensor_check", "will the sensors saturate?"],
      ["power_budget", "batteries through the pad wait"],
      ["radio_link", "tracker range in flight and after landing"],
    ],
  },
  {
    q: "Reports, comparisons and data",
    why: "For reviews, other tools and after a flight.",
    tools: [
      ["generate_report", "design review report with the plots Launch Canada asks for"],
      ["compare_designs", "what changed since the last version or review"],
      ["progress_log", "this week's progress, written for the team channel"],
      ["compare_flight", "your altimeter log against the simulation"],
      ["export_geometry", "STL, DXF and the CFD run matrix"],
      ["import_aero_table", "use RASAero or CFD drag and CP"],
    ],
  },
];

/** A design review, in the order that saves rework. */
export const REVIEW: [string, string][] = [
  ["design_status", "See what is missing."],
  ["check_requirements", "Fix every FAIL."],
  ["aero_analysis", "Margin through Mach."],
  ["recovery_analysis", "Opening loads and pins."],
  ["fin_flutter", "Fins clear of flutter."],
  ["structural_loads", "Joints and couplers."],
  ["monte_carlo", "Landing area."],
  ["generate_report", "Write it up."],
];

/** The Standards page's sections: what each holds and which analyses read it. */
export const STD_SECTIONS: Record<string, { holds: string; usedBy: string }> = {
  competition: {
    holds: "The rule set the rule check applies (Launch Canada 2027 by default), the competition year and whether you enter the staged category.",
    usedBy: "Rule check, Readiness, Targets, the design review report, and the rule limits in the optimizers and parachute charts.",
  },
  launchSite: {
    holds: "Rail length and angle, the design wind speed, and the site's altitude, latitude and longitude.",
    usedBy: "New simulations' launch conditions, the rule check's wind case, Rail buttons, Fin stress and Airframe loads (gust), Launch-day forecast.",
  },
  recovery: {
    holds: "Opening-load factors (Cx, fill constant), safety factors, your shear pins and their ratings, black powder density, packing factors, and the landing energy limit.",
    usedBy: "Recovery loads, Late deployment, Opening shock, Shear pins, Ejection charge, Recovery bay fit, Landing energy, Recovery sections.",
  },
  structures: {
    holds: "Flutter margin and load safety factor, and per material: shear and Young's moduli, Poisson's ratio, strength and maximum service temperature.",
    usedBy: "Fin flutter, Fin stress (FEA), Airframe loads, Material trade, Aerodynamic heating, and the fin optimizer's flutter limit.",
  },
  electronics: {
    holds: "The flight computer's sensors, altimeter choices (backup delays, Mach lockout), battery circuits and e-matches, and the tracker radio.",
    usedBy: "Sensor ranges, Flight software test data, Altimeter settings, Power budget, Radio link.",
  },
};

/** What a standards value means, by its path below the section ("recovery.openingForceCoefficient"). */
export const STD_HELP: Record<string, string> = {
  "launchSite.name": "Name of the site, for reports.",
  "launchSite.altitudeMsl": "Pad height above sea level, e.g. 300 m. Sets the air density at deployment and for the whole flight.",
  "launchSite.latitude": "Decimal degrees, north positive, e.g. 47.98. Used by the forecast and the landing map.",
  "launchSite.longitude": "Decimal degrees, east positive, west negative, e.g. -81.85.",
  "launchSite.railLength": "Rail length new simulations use, e.g. 18.5 ft for Launch Canada's 1515 rail.",
  "launchSite.launchAngleFromVertical": "Rail tilt from vertical, e.g. 6 deg (= 84° above the horizon).",
  "launchSite.designWindSpeed": "The wind the design must handle, e.g. 30 km/h: the rule check's wind case, the crosswind for rail buttons, the gust for fin and airframe loads.",
  "recovery.openingForceCoefficient": "Cx: peak opening force ÷ steady drag at the same speed. 1.4 is Knacke's infinite-mass value.",
  "recovery.openingLoadMethod": "infinite_mass (Cx × q × CdA, conservative) or finite_mass (fill simulation) for the design load.",
  "recovery.canopyFillConstant": "n in fill time = n × diameter ÷ speed. Lower is more conservative.",
  "recovery.inflationExponent": "How the canopy area grows while filling. Lower is more conservative.",
  "recovery.shearPinHoldSafetyFactor": "Pins must hold this × the load trying to open the joint, e.g. 2.",
  "recovery.harnessSafetyFactor": "Shock cord, links and eye bolts rated for this × the opening load, e.g. 2.",
  "recovery.ejectionForceSafetyFactor": "Charges sized for this × the force to break the pins, e.g. 1.5.",
  "recovery.backupChargeFactor": "Backup charge = primary × this, e.g. 1.25.",
  "recovery.blackPowderBulkDensity": "For the charge well volume, e.g. 1.0 g/cm3.",
  "recovery.packingFactor": "Real packed volume ÷ calculated, e.g. 2 when nothing is measured. Measure yours and add it below.",
  "recovery.maxLandingEnergy": "Highest kinetic energy at touchdown per separately landing section, e.g. 75 ft-lbf.",
  "recovery.shockCord.width": "Width of your shock cord, for bay volume.",
  "recovery.shockCord.thickness": "Thickness of your shock cord, for bay volume.",
  "structures.flutterMinMargin": "Flutter speed must be at least this × the airspeed everywhere in the flight, e.g. 1.5.",
  "structures.flutterConstant": "The constant in the NACA TN 4197 flutter formula; leave it unless you have a reason.",
  "structures.loadSafetyFactor": "Fins and airframe sized for this × the flight load, e.g. 2.",
  "electronics.altimeter.backupDrogueDelay": "Backup drogue fires this long after apogee, e.g. 1 s.",
  "electronics.altimeter.backupMainOffset": "Backup main fires this much lower than the primary, e.g. 100 ft.",
  "electronics.altimeter.baroUnreliableAboveMach": "Above this Mach the barometer reads wrong (shock waves), e.g. 0.7.",
  "electronics.altimeter.machLockoutMargin": "Lockout kept on this long after dropping below that Mach, e.g. 1 s.",
  "electronics.power.padWait": "Time the electronics are on at the pad, e.g. 2 h.",
  "electronics.power.recoveryTime": "Time on after landing until found, e.g. 2 h.",
  "electronics.power.capacityDerating": "Usable fraction of rated battery capacity, e.g. 0.8.",
  "electronics.power.ematch.resistance": "E-match bridge resistance in ohms, e.g. 1.6.",
  "electronics.power.ematch.allFireCurrent": "Current that always fires it, in amps, e.g. 1.0.",
  "electronics.power.ematch.wiringResistance": "Wires and switch, in ohms, e.g. 0.3.",
  "electronics.power.ematch.currentMargin": "Current delivered must be this × the all-fire current, e.g. 2.",
  "electronics.radio.frequencyMhz": "Tracker frequency in MHz, e.g. 915 or 433.",
  "electronics.radio.txPowerDbm": "Transmit power in dBm: 20 dBm = 100 mW.",
  "electronics.radio.txAntennaGainDbi": "Rocket antenna gain, e.g. 2.15 dBi for a dipole.",
  "electronics.radio.rxAntennaGainDbi": "Ground antenna gain, e.g. 7 dBi for a 3-element Yagi.",
  "electronics.radio.rxSensitivityDbm": "Weakest signal the receiver decodes, e.g. -123 dBm.",
  "electronics.radio.lossesDb": "Cables, connectors, airframe, e.g. 3 dB.",
  "electronics.radio.requiredMarginDb": "Margin wanted above the sensitivity, e.g. 10 dB.",
  "electronics.radio.groundStationHeight": "Ground antenna height, e.g. 2 m.",
  "electronics.radio.rocketAntennaHeightOnGround": "Rocket antenna height after landing, e.g. 0.1 m in the grass.",
  shearPins: "Your shear pins and the force that breaks one. Add each size you use; analyses offer these by name.",
  measuredPackingFactors: "Packing factors you measured on real bays; use them instead of the default.",
  shearModulus: "In-plane shear modulus G per material (names separated by | match the design's material names). Fin flutter uses it.",
  youngsModulus: "Young's modulus E per material: fin FEA and shell buckling.",
  poissonRatio: "Poisson's ratio per material: fin FEA.",
  strength: "Allowable stress per material (yield for metals, failure for laminates): fin FEA, airframe loads, material trade.",
  maxServiceTemperature: "Highest temperature per material (resin glass transition, plastic softening): aerodynamic heating.",
  sensors: "Your flight computer's sensors with range, noise and resolution; replace the typical ones with yours.",
  circuits: "Each battery circuit: battery, capacity, average draw and what it powers.",
};

/** Keyboard shortcuts and habits worth knowing. */
export const TIPS: [string, string][] = [
  [keys("K"), "Search anything: an analysis by what it is for (FEA, chute, wind), a page, a part, a setting."],
  ["ⓘ", "Beside every analysis and input: what it needs, what it gives, an example in your units."],
  ["*", "A needed input. Empty inputs use the design and the team standards."],
  [keys("Z"), "Undo any edit, including those an analysis or the assistant made."],
  [keys("S"), `Save; ${keys("S", true)} saves as.`],
  ["?", "Opens these tips from anywhere; the Help button at the foot of the left rail has the rest of the Guide and the tour."],
  ["Search", `Search (${keys("K")}) finds a setting of the selected part ("wall thickness"), a team standard ("pin rating"), a target, a view mode or an action, and takes you to it.`],
  ["Folding", "Every panel folds from its title; right-click a title to fold the others. Properties sections, analysis groups, target groups and Settings fold too."],
  ["RASAero", "Above Mach 0.8 the Design page shows RASAero vs OpenRocket: import RASAero II's aero export and both models' apogee, speed, stability, CD and CP sit side by side."],
  ["Right-click", "Anything: a part, a motor, a simulation, a target, a standard, an analysis, a result, a panel title. Each gives its quick commands; empty space gives the page's own and the app's (search, undo, save)."],
  ["Parts", `Drag in the list to move a part into another; Alt+↑/↓ moves it up or down; ${keys("D")} duplicates; ${keys("C")} and ${keys("V")} copy and paste into the selected part; right-click for all of these.`],
  ["Move", "Drag the selected part along the rocket in the view: it lines up with the edges of the parts near it (hold Alt to place it freely). Properties › Position › Line up puts it at the front, middle or back of its parent."],
  ["↑ ↓", "In a number box, the arrow keys step the value (Shift: ten times more, Alt: ten times less)."],
  ["Find", "The box at the top of Properties finds any setting of the part, folded sections included."],
  ["Units", "Type a unit with any number (3 in, 20 km/h, 270 deg). Settings switches metric and imperial."],
  ["Problems", "The warning triangle at the top right lists errors and flight warnings, and reports a bug."],
  ["Assistant", "The robot button: ask Claude or ChatGPT about the design; it uses the same tools."],
];
