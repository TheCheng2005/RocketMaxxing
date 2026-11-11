// What to type in each analysis input: an example in the team's unit system, the unit a bare number is read in,
// and a plain explanation where the engine's own description (written for the assistant) is not enough.
// Keys are input names; "tool.input" entries take precedence for one analysis.

/** One example, or [metric, imperial]. */
type Ex = string | [string, string];

export interface FieldHelp {
  ex?: Ex;
  /** The unit a number typed without one is read in (else the unit of the example; "" for SI). */
  unit?: string;
  help?: string;
}

const DIR =
  "Compass degrees, clockwise from north: 0 = north, 90 = east, 180 = south, 270 = west.";

export const FIELDS: Record<string, FieldHelp> = {
  simulation: {
    help: "Which saved simulation to fly: its launch conditions and configuration. Active configuration flies the first simulation of the configuration chosen in the top bar (one is made with the team's launch site if there is none).",
  },
  configuration: { help: "Which motor configuration to fly. Active is the one chosen in the top bar." },
  pinType: { ex: "4-40 nylon", help: "A shear pin from the team standards (Standards page, recovery), with its rated strength: 4-40 nylon holds 140 N each by default." },
  // Launch conditions
  windSpeed: { ex: ["20 km/h", "12 mph"], help: "Average wind at the pad, measured 10 m (33 ft) above the ground." },
  windDirection: {
    ex: "270 deg",
    unit: "deg",
    help: `The direction the wind blows FROM. ${DIR} A south-west wind (blowing from the south-west toward the north-east) is 225.`,
  },
  windTurbulence: {
    ex: "0.1",
    help: "Gust strength as a fraction of the wind speed: 0.1 means gusts of about ±10 % (±2 km/h in a 20 km/h wind). 0.05 is calm, 0.2 gusty.",
  },
  railLength: { ex: ["5.6 m", "18.5 ft"], help: "Length of the rail or tower the rocket slides along before it flies free." },
  launchAngle: {
    ex: "6 deg",
    unit: "deg",
    help: "Tilt of the rail away from vertical: 0 = straight up, 6 = 84 degrees above the horizon.",
  },
  launchDirection: {
    ex: "90 deg",
    unit: "deg",
    help: `The direction the rail leans toward, so the way the rocket heads. ${DIR} With Launch into wind set to Yes the rail is turned into the wind instead.`,
  },
  launchIntoWind: { help: "Yes turns the rail to lean into the wind, as range officers usually do, so the rocket lands closer to the pad." },
  launchSiteAltitude: { ex: ["300 m", "1000 ft"], help: "Height of the pad above sea level. Sets the air density for the whole flight." },
  latitude: {
    ex: "32.99",
    help: "Decimal degrees, north positive, south negative: 32.99 is 32.99° N (Spaceport America). Sets gravity and the Earth's rotation.",
  },
  longitude: {
    ex: "-106.97",
    help: "Decimal degrees, east positive, west negative: -106.97 is 106.97° W (Spaceport America).",
  },
  temperature: { ex: ["25 C", "77 F"], help: "Air temperature at the pad. The air above cools at the standard rate (6.5 °C per km)." },

  // Probation
  glppVolume: { ex: ["6 L", "366 in3"], help: "Total volume of gaseous and liquid pressurants and propellants: ullage, pressurant, oxidizer and fuel tanks. Solid fuel does not count." },
  staticFireIsp: { ex: "180", help: "Specific impulse measured in the static fire, in seconds." },
  totalImpulse: { ex: ["5000 Ns", "1124 lbf-s"], help: "Total impulse measured in the static fire. With Propellant mass it gives the Isp." },
  propellantMass: { ex: ["2.8 kg", "6.2 lb"] },
  targetAltitude: { ex: ["3000 m", "10000 ft"] },
  actualAltitude: { ex: ["2850 m", "9350 ft"] },
  predictedAltitude: { ex: ["3100 m", "10200 ft"] },
  vehicleTotalImpulse: { ex: ["10240 Ns", "2302 lbf-s"] },

  // Stability and drag
  stage: { ex: "Sustainer", help: "A stage name: the analysis covers that stage and everything above it, flying alone after the stages below separate. Type all for every stack." },
  machs: { ex: "0.3, 0.8, 1.2, 2", help: "Mach numbers for the table, separated by commas." },
  breakdownMach: { ex: "0.3" },
  maxMach: { ex: "2" },
  targetStability: {
    ex: "2",
    help: "In calibers (body diameters) between the centre of gravity and the centre of pressure. On a 4 in (102 mm) rocket, 2 cal = 8 in (203 mm).",
  },
  position: { ex: ["50 mm", "2 in"], help: "Where the ballast's centre sits, measured aft from the front of the part that holds it." },
  cantAngles: { ex: "0, 0.25, 0.5, 1", unit: "deg", help: "Fin cant (misalignment) angles to try, in degrees, separated by commas." },
  maxRollRate: { ex: "2", help: "Highest acceptable roll rate, in revolutions per second." },
  noseLengths: { ex: ["40 cm, 50 cm", "16 in, 20 in"], help: "Nose cone lengths to try, separated by commas. Each is flown with every nose shape." },
  designMach: { ex: "0.9" },
  minStability: { ex: "1.5", help: "Minimum ascent stability in calibers; options below it are flagged." },
  maxStability: { ex: "4", help: "Maximum ascent stability in calibers." },

  // Recovery
  pinStrength: { ex: ["140 N", "31 lbf"], help: "Force that shears one pin. Overrides the pin type's rating." },
  cx: {
    ex: "1.4",
    help: "Opening force coefficient: peak opening force ÷ the canopy's steady drag at the same speed. 1.4 (Knacke, table 5-1) is the team default.",
  },
  altitudeMsl: { ex: ["1500 m", "5000 ft"], help: "Altitude above sea level where the descent rate matters; sets the air density." },
  altitudeAgl: { ex: ["300 m", "1000 ft"], help: "Altitude above the pad; the pad's own altitude comes from the team standards." },
  airDensity: { ex: ["1.1 kg/m3", "0.069 lb/ft3"] },
  mass: { ex: ["4.5 kg", "10 lb"], help: "Mass hanging under the parachute (the rocket after burnout, or the section it carries)." },
  otherCdA: { ex: ["0.05 m2", "0.54 ft2"], help: "Drag area (Cd × area) of what is already open, e.g. the drogue still attached when the main opens." },
  cd: { ex: "1.5", help: "Drag coefficient referenced to the canopy's nominal (flat) area. Vendors often quote Cd on the projected area (e.g. 2.2): convert it, or enter that area too." },
  cds: { ex: "0.8, 1.5, 2.2", help: "Up to three drag coefficients to compare, separated by commas." },
  targetDescentRate: { ex: ["6 m/s", "20 ft/s"], help: "Steady descent speed wanted under this parachute. Empty: the rule set's recommended rate." },
  minDiameter: { ex: ["60 cm", "24 in"] },
  maxDiameter: { ex: ["2.4 m", "96 in"] },
  diameter: { ex: ["1.5 m", "60 in"] },
  count: { ex: "1", help: "Number of identical canopies sharing the load." },
  tolerance: { ex: "0.15", help: "How far a catalog parachute's descent rate may be from the target: 0.15 = within 15 %." },
  delays: { ex: "0, 1, 2, 3, 4", unit: "s", help: "Seconds late, separated by commas. 0 is the planned deployment." },
  pinCount: { ex: "4" },
  joints: { help: "Airframe parts whose forward end separates in flight, separated by commas. Empty: found from where the parachutes are." },
  holdForce: { ex: ["900 N", "200 lbf"], help: "Load the joint must hold closed, e.g. the drogue's opening load from Recovery loads." },
  safetyFactor: { ex: "2", help: "Multiplies the load: 2 means sized for twice the expected load." },
  bayDiameter: { ex: ["98 mm", "3.9 in"], help: "Inner diameter of the bay." },
  bayLength: { ex: ["250 mm", "10 in"], help: "Free length of the bay the gas fills (bulkhead to bulkhead, minus what is packed in it)." },
  bayVolume: { ex: ["1.9 L", "116 in3"], help: "Free volume of the bay, instead of diameter and length." },
  pressure: { ex: ["100 kPa", "15 psi"], help: "Pressure the charge should build in the bay. 70–100 kPa (10–15 psi) is common." },
  extraForce: { ex: ["50 N", "11 lbf"], help: "Friction and fit to overcome besides the shear pins." },
  backupFactor: { ex: "1.25", help: "Backup charge = primary charge × this: 1.25 = 25 % more powder." },
  velocity: { ex: ["40 m/s", "130 ft/s"], help: "Airspeed when the lines go taut, including sideways speed and wind, not only the descent rate." },
  area: { ex: ["1.8 m2", "19 ft2"] },
  pathAngle: { ex: "90 deg", unit: "deg", help: "Flight path below horizontal when it deploys: 90 = falling straight down, 0 = flying level." },
  fillConstant: { ex: "4", help: "Canopy fill constant n: fill time = n × diameter ÷ speed. Lower is more conservative (a harder opening)." },
  inflationExponent: { ex: "1", help: "Shape of the area growth while the canopy fills. Lower is more conservative." },
  packingFactor: { ex: "2", help: "Real packed volume ÷ calculated volume: 2 = items take twice their calculated volume." },
  availableLength: { ex: ["300 mm", "12 in"] },
  availableVolume: { ex: ["1.5 L", "92 in3"] },
  manufacturer: { ex: "Fruity Chutes" },
  limit: { ex: "40" },
  descentRate: { ex: ["6 m/s", "20 ft/s"] },
  energyLimit: { ex: ["102 J", "75 ft-lbf"], help: "Highest kinetic energy allowed for each section at touchdown." },

  // Structures
  shearModulus: { ex: ["26 GPa", "3770 ksi"], help: "In-plane shear modulus G of the fin material: 26 GPa for 6061-T6 aluminium, about 2.9 GPa for G10. Empty: from the material via the team standards." },
  thickness: { ex: ["3.2 mm", "1/8 in"], help: "What-if fin thickness. The design is not changed." },
  youngsModulus: { ex: ["69 GPa", "10000 ksi"], help: "In-plane Young's modulus E: 69 GPa for 6061-T6 aluminium. Empty: from the team standards." },
  poissonRatio: { ex: "0.33", help: "In-plane Poisson's ratio: 0.33 for aluminium." },
  allowableStress: { ex: ["276 MPa", "40 ksi"], help: "Highest stress the material may carry: the yield stress for metals (276 MPa for 6061-T6), the failure stress for laminates." },
  gustSpeed: { ex: ["30 km/h", "19 mph"], help: "Sideways gust that hits the rocket at its maximum dynamic pressure. Empty: the rule set's maximum ground wind." },
  modes: { ex: "4", help: "How many natural vibration frequencies to compute." },
  outDir: { ex: "fea/fins" },
  noseTipRadius: { ex: ["5 mm", "0.2 in"] },
  parts: { ex: "fins, airframe", help: "What to build from each material: fins, airframe, body tubes, nose cone, transitions, or part names, separated by commas." },
  materials: { ex: "Fiberglass, Carbon fiber, Aluminum", help: "OpenRocket material names, separated by commas." },
  thicknesses: { ex: ["3 mm, 4.5 mm", "1/8 in, 3/16 in"], help: "Stock thicknesses to try, separated by commas." },
  meop: { ex: ["5.5 MPa", "800 psi"], help: "Maximum expected operating pressure: the relief valve's set pressure." },
  burstPressure: { ex: ["14 MPa", "2000 psi"] },
  outerDiameter: { ex: ["152 mm", "6 in"] },
  wallThickness: { ex: ["3.2 mm", "1/8 in"] },
  ultimateStrength: { ex: ["290 MPa", "42 ksi"], help: "Minimum ultimate tensile strength of the alloy and temper from the literature: 290 MPa (42 ksi) for 6061-T6." },
  weldKnockdown: { ex: "1.2" },
  proofPressure: { ex: ["8.3 MPa", "1200 psi"], help: "Planned hydrostatic proof test pressure; the rules ask for at least 1.5 × MEOP." },

  // Launch and avionics
  couplerLength: { ex: ["300 mm", "12 in"] },
  switchBand: { ex: ["25 mm", "1 in"], help: "Length of the exposed band between the tubes; 0 for none." },
  chargeMass: { ex: ["12 g", "0.42 oz"] },
  sledMass: { ex: ["120 g", "4.2 oz"] },
  bulkheadThickness: { ex: ["6 mm", "1/4 in"] },
  bulkheadMaterial: { ex: "Birch" },
  time: { ex: "2027-08-21T10:00", help: "Launch time in the site's local time, as year-month-day T hour:minute." },
  top: { ex: ["12 km", "40000 ft"] },
  forward: { ex: ["1.2 m", "48 in"], help: "Centre of the forward button, measured up from the rocket's aft end." },
  aft: { ex: ["50 mm", "2 in"], help: "Centre of the aft button, measured up from the rocket's aft end." },
  standoff: { ex: ["0 m", "0 in"] },
  clearance: { ex: ["1 mm", "0.04 in"], help: "Play of the buttons in the rail slot." },
  crosswind: { ex: ["30 km/h", "19 mph"] },
  minSpacing: { ex: ["150 mm", "6 in"] },
  rate: { ex: "100", help: "Samples per second (up to 1000)." },
  gpsRate: { ex: "10", help: "GPS fixes per second." },
  padTime: { ex: "5 s", unit: "s" },
  restTime: { ex: "3 s", unit: "s" },
  branch: { ex: "Sustainer" },
  seed: { ex: "1" },
  frequencyMhz: { ex: "915", help: "Radio frequency in MHz." },
  txPowerDbm: { ex: "20", help: "Transmit power in dBm: 20 dBm = 100 mW, 30 dBm = 1 W." },
  txAntennaGainDbi: { ex: "2" },
  rxAntennaGainDbi: { ex: "7", help: "Ground antenna gain in dBi: about 7 for a 3-element Yagi." },
  rxSensitivityDbm: { ex: "-120", help: "Weakest signal the receiver decodes at the data rate used, in dBm (negative)." },
  lossesDb: { ex: "3", help: "Cables, connectors, the airframe and polarisation, in dB." },
  requiredMarginDb: { ex: "10" },
  groundStationHeight: { ex: ["2 m", "6 ft"] },
  groundStationEast: { ex: ["500 m", "1600 ft"], help: "Distance east of the pad; west is negative." },
  groundStationNorth: { ex: ["0 m", "0 ft"], help: "Distance north of the pad; south is negative." },
  landingDispersion: { ex: ["1.5 km", "1 mi"], help: "Extra distance to the far edge of the landing area, e.g. the 95 % radius from Monte Carlo." },
  padWait: { ex: "2 h", unit: "h", help: "Time the electronics are on at the pad before launch." },
  recoveryTime: { ex: "4 h", unit: "h", help: "Time the electronics stay on after landing until the rocket is found." },
  derating: { ex: "0.8", help: "Usable fraction of the battery's rated capacity: 0.8 = 80 %." },
  ematchResistance: { ex: "1.5", help: "E-match bridge resistance in ohms." },
  allFireCurrent: { ex: "0.5", help: "Current that fires the e-match every time, in amps." },
  groundSpeed: { ex: ["15 km/h", "9 mph"], help: "Wind speed at 10 m (33 ft) above the ground." },
  direction: { ex: "270 deg", unit: "deg", help: `The direction the wind blows FROM. ${DIR}` },
  exponent: { ex: "0.14", help: "Wind shear exponent α in v = v_ground × (h / 10 m)^α: about 0.14 (1/7) over open land, 0.1 over water." },
  turbulence: { ex: "0.1", help: "Gust strength as a fraction of each level's speed: 0.1 = ±10 %." },

  // Studies
  runs: { ex: "200", help: "Number of flights (up to 1000). 100 runs take a few seconds." },
  windSpeedSd: { ex: ["7 km/h", "4 mph"], help: "One standard deviation of the wind speed: 68 % of the runs fall within ± this of the mean." },
  windDirectionSd: { ex: "30 deg", unit: "deg" },
  launchAngleSd: { ex: "1 deg", unit: "deg" },
  launchDirectionSd: { ex: "5 deg", unit: "deg" },
  massSd: { ex: "0.05", help: "Uncertainty of the structure's mass as a fraction: 0.05 = ±5 % (one standard deviation). Motors excluded." },
  dragSd: { ex: "0.1", help: "Uncertainty of the airframe's drag coefficient as a fraction: 0.1 = ±10 %." },
  thrustSd: { ex: "0.03", help: "Uncertainty of the motor's thrust as a fraction: 0.03 = ±3 %." },
  chuteCdSd: { ex: "0.1", help: "Uncertainty of each parachute's Cd as a fraction: 0.1 = ±10 %." },
  kmlPath: { ex: "landing.kml" },
  siteLatitude: { ex: "32.99" },
  siteLongitude: { ex: "-106.97" },
  targetApogee: { ex: ["3048 m", "10000 ft"], help: "Apogee above the pad to aim for." },
  maxCandidates: { ex: "60" },
  maxOverhang: { ex: ["50 mm", "2 in"], help: "How far a motor may stick out of the back of the mount." },
  maxLength: { ex: ["700 mm", "28 in"] },
  minClass: { ex: "K", help: "Lowest impulse class letter." },
  maxClass: { ex: "M", help: "Highest impulse class letter." },
  certLevel: { ex: "L2", help: "Certification level: L1 allows H–I, L2 J–L, L3 M–O." },
  designationContains: { ex: "M1850" },
  parameter: { ex: "windSpeed", help: "A launch condition (windSpeed, launchAngle, railLength, launchSiteAltitude, temperature), or a property of the part chosen in Component, e.g. height for fin span." },
  values: { ex: ["10 km/h, 20 km/h, 30 km/h", "5 mph, 10 mph, 20 mph"], unit: "", help: "Values to try, each with its unit, separated by commas." },
  minRootChord: { ex: ["100 mm", "4 in"] },
  maxRootChord: { ex: ["300 mm", "12 in"] },
  minSpan: { ex: ["50 mm", "2 in"] },
  maxSpan: { ex: ["200 mm", "8 in"] },
  minTipChord: { ex: ["13 mm", "0.5 in"] },
  maxSweepAngle: { ex: "60 deg", unit: "deg" },
  minFlutterMargin: { ex: "1.5", help: "Flutter speed ÷ airspeed that every point of the flight must keep: 1.5 = flutter only at 1.5 × the fastest speed flown." },
  maxEvaluations: { ex: "48", help: "Simulations to spend; more finds a slightly better answer." },
  minRailExit: { ex: ["30 m/s", "100 ft/s"] },
  minApogee: { ex: ["2700 m", "9000 ft"] },

  // Compare and import, files
  url: { ex: "https://shop.example.com/parachute-60in" },
  baselinePath: { ex: "rocket-PDR.ork" },
  revision: { ex: "HEAD~1", help: "A git commit, tag or branch of this design's own file, e.g. HEAD~1 or a tag like PDR." },
  label: { ex: "PDR", help: "A milestone name for this record, to compare with later." },
  note: { ex: "after the fin review" },
  since: { ex: "PDR", help: "last, a milestone label (PDR) or a date (2026-10-01)." },
  cpUnit: { ex: "in" },
  source: { ex: "RASAero rev B" },
  altitudeUnit: { ex: "ft" },
  outputDir: { ex: "reports/PDR" },
  title: { ex: "Preliminary design review" },
  segments: { ex: "96" },
  angleOfAttack: { ex: "0, 2, 4", unit: "deg", help: "Angles of attack in degrees, separated by commas." },
  targetLaunchMass: { ex: ["25 kg", "55 lb"] },
  contingency: { ex: "0.1", help: "Growth allowance on everything not weighed yet: 0.1 = 10 %." },
  massUnit: { ex: "g" },
  lengthUnit: { ex: "mm" },

  // One analysis only
  "fin_flutter.shearModulus": { ex: ["26 GPa", "3770 ksi"], help: "Shear modulus G for all fin sets: 26 GPa for 6061-T6 aluminium, about 2.9 GPa for G10. Empty: each fin's material via the team standards." },
  "fin_fea.pressure": { ex: ["5 kPa", "0.7 psi"], help: "A uniform pressure on one face of the fin instead of the flight load, e.g. to match a bench load test." },
  // simulate_engine
  throatDiameter: { ex: ["22 mm", "0.875 in"], help: "The narrowest point of the nozzle." },
  exitDiameter: { ex: ["45 mm", "1.75 in"], help: "The nozzle's exit. Or give the expansion ratio (exit area / throat area) under More options." },
  cstar: { ex: "1550 m/s", help: "Characteristic velocity of your propellants at your chamber pressure, from NASA CEA, RocketCEA or ProPEP. One value for a solid; for a hybrid or liquid the table is better." },
  cstarTable: { ex: "3:1450, 4:1540, 6:1620, 8:1610", help: "c* (m/s) against oxidizer/fuel mixture ratio, as mixture:c* pairs. A hybrid's mixture ratio shifts through the burn; the table lets c* follow it." },
  gamma: { ex: "1.2", help: "Ratio of specific heats of the combustion gas, from the same thermochemistry run (1.15 to 1.3 for most propellants)." },
  "simulate_engine.designation": { ex: "Maple-H1", help: "A name makes the engine a motor in the motor list (with maker, diameter, length and dry mass)." },
  "simulate_engine.manufacturer": { ex: "our team" },
  "simulate_engine.diameter": { ex: ["98 mm", "4 in"], help: "Outer diameter of the engine as it sits in the rocket (with its tank, for a hybrid or liquid)." },
  "simulate_engine.length": { ex: ["1.5 m", "60 in"], help: "Length of the whole engine: tank, chamber and nozzle." },
  dryMass: { ex: ["6 kg", "13 lb"], help: "The engine's mass with no propellant in it." },
  oxidizerMass: { ex: ["6 kg", "13 lb"] },
  fuelMass: { ex: ["3 kg", "6.6 lb"] },
  tankPressure: { ex: ["50 bar", "725 psi"], help: "Oxidizer tank pressure at ignition (nitrous oxide at 20 C is about 50 bar)." },
  tankPressureEnd: { ex: ["35 bar", "500 psi"], help: "Tank pressure when the liquid oxidizer runs out. Nitrous oxide cools as it empties and typically loses a quarter to a third." },
  chamberPressure: { ex: ["20 bar", "300 psi"] },
  injectorHoleDiameter: { ex: ["1.5 mm", "0.06 in"] },
  injectorHoleCount: { ex: "12" },
  dischargeCoefficient: { ex: "0.4", help: "Share of the ideal flow the injector passes: about 0.6 to 0.8 for a plain liquid, 0.3 to 0.5 for nitrous oxide, which flashes in the holes. Best from a cold-flow test." },
  oxidizerDensity: { ex: "750 kg/m3", help: "Liquid oxidizer density: nitrous oxide about 750 kg/m3 at 20 C, liquid oxygen 1141." },
  oxidizerFlow: { ex: "0.6", help: "kg/s, when the flow is regulated or known from a cold-flow test; leave empty to work it out from the injector." },
  portDiameter: { ex: ["30 mm", "1.2 in"], help: "Diameter of the fuel grain's port before it burns." },
  grainOuterDiameter: { ex: ["90 mm", "3.5 in"] },
  grainCoreDiameter: { ex: ["25 mm", "1 in"] },
  grainLength: { ex: ["500 mm", "20 in"], help: "Hybrid: the fuel grain's length. Solid: the length of one grain." },
  grainCount: { ex: "3" },
  fuelDensity: { ex: "920 kg/m3", help: "Paraffin about 920 kg/m3, HTPB 930, ABS 1040, PMMA 1180." },
  propellantDensity: { ex: "1750 kg/m3" },
  regressionCoefficient: { ex: "0.155", help: "a in r = a x Gox^n, with r in mm/s and Gox in kg/m2/s. Paraffin with nitrous oxide is about 0.155 (n 0.5); HTPB about 0.05 to 0.2 (n 0.6 to 0.8). Use your own test data when you have it." },
  regressionExponent: { ex: "0.5" },
  burnRateCoefficient: { ex: "5", help: "a in r = a x Pc^n: r in mm/s with Pc in MPa (or in/s with psi; choose under burn rate units)." },
  burnRateExponent: { ex: "0.35" },
  mixtureRatio: { ex: "2.3", help: "Oxidizer / fuel by mass that the injector delivers. Default: what is loaded, so both run out together." },
  ullageFraction: { ex: "0.3", help: "Blowdown: the share of the tanks' volume that is pressurant gas at ignition. More gas holds the pressure up longer." },
  expansionRatio: { ex: "4.2", help: "Exit area / throat area, instead of the exit diameter." },
  cstarEfficiency: { ex: "0.9", help: "Share of the ideal c* the chamber delivers: about 0.95 for solids and liquids, 0.85 to 0.95 for hybrids." },
  nozzleEfficiency: { ex: "0.97", help: "Losses in the nozzle (divergence, friction): 0.97 to 0.98 for a 15 degree cone." },
  "rank_motors.minDiameter": { ex: "54 mm" },
  "rank_motors.maxDiameter": { ex: "75 mm" },
  "rank_motors.manufacturer": { ex: "CTI" },
  "export_geometry.angleOfAttack": { ex: "0, 2, 4", unit: "deg" },
  "flight_card.path": { ex: "flight-card.md" },
  "sensor_data.path": { ex: "sensors.csv" },
  "export_flight_data.path": { ex: "flight.csv" },
  "altimeter_settings.path": { ex: "altimeter-card.md" },
  "compare_designs.path": { ex: "reviews/changes-since-PDR.md" },
  "progress_log.path": { ex: "progress.md" },
};

export function fieldHelp(tool: string, key: string): FieldHelp {
  return FIELDS[`${tool}.${key}`] ?? FIELDS[key] ?? {};
}

/** The example in the chosen unit system. */
export function example(h: FieldHelp, imperial: boolean): string {
  if (!h.ex) return "";
  return typeof h.ex === "string" ? h.ex : h.ex[imperial ? 1 : 0];
}

/** The unit a bare number is read in: the field's own, else the example's (with any number in front). */
export function bareUnit(h: FieldHelp, imperial: boolean): string {
  if (h.unit !== undefined) return h.unit;
  const m = example(h, imperial).split(",")[0].trim().match(/^[-+]?[\d./]+\s*(.+)$/);
  return m && !/^\d/.test(m[1]) ? m[1] : "";
}
