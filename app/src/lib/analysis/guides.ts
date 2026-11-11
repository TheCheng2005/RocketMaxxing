// The ⓘ guide of each analysis: what to have ready before running it, what comes back, how it works and what it is
// not. Written for a student team that has not read the engine's documentation.

export interface Guide {
  /** What has to be in the design or at hand first. */
  needs: string[];
  /** What the result holds. */
  gives: string[];
  /** The method, in a sentence or two. */
  how?: string;
  /** What people might expect that it does not do. */
  not?: string;
  /** A worked example with numbers. */
  example?: string;
}

export const MOTOR = "A motor in the active configuration (Motors page): the analysis reads the simulated flight.";
const CHUTES = "Parachutes with their deployment set (event, altitude or delay) in the design.";

export const GUIDES: Record<string, Guide> = {
  check_requirements: {
    needs: [MOTOR],
    gives: [
      "PASS / WARN / FAIL for each rule with its reference: launch angle, rail exit speed, thrust-to-weight, ascent stability and over-stability, L:D, damping, descent rates, main deployment altitude, dual-event recovery.",
      "A second flight in the rule set's maximum ground wind (Include wind case).",
      "A manual checklist of what software cannot check (electronics, radio, structures, operations).",
    ],
    example: "If the rail exit speed is 22 m/s against a 30.5 m/s (100 ft/s) minimum, it reads FAIL. A longer rail, a lighter rocket or a motor with more initial thrust fixes it.",
  },
  design_status: {
    needs: ["An open design. Nothing else: it lists what is missing."],
    gives: ["Rule failures and warnings in short, what is not set up yet (motor, recovery, electronics, launch site), unsaved changes, and the next steps in order."],
  },
  verify_claims: {
    needs: ["The numbers your notes, slides or messages state, one per row: what (apogee, dry mass, main descent rate, motor, or a part and property) and the value as written with its unit."],
    gives: ["Each claim as MATCH, MISMATCH (with the design's value and the difference) or UNCHECKED (and why)."],
    example: "If the notes say \"apogee ~10,000 ft\" with tolerance 5 % and the design flies to 9,120 ft: MISMATCH by -880 ft (-8.8 %). Either the notes or the design is out of date.",
  },
  advanced_probation: {
    needs: ["For hybrid, liquid or tribrid entries: the GLPP volume (*). For the full check, static-fire Isp (or total impulse and propellant mass) and the target, actual and predicted altitudes."],
    gives: ["The Launch Canada advanced probation level, the static-fire Isp needed, and AASI = min(1, actual ÷ target altitude) × Isp with whether it clears the level."],
    example: "Isp 180 s, target 3,048 m, reached 2,850 m: AASI = 0.935 × 180 = 168 s.",
  },
  aero_analysis: {
    needs: ["Parts with their shapes (nose, tubes, fins). A motor adds the flight's top speed and the burnout CG."],
    gives: [
      "Drag coefficient split into friction, pressure and base drag, CP position, CNα and static margin at each Mach number.",
      "Each part's share of the drag at one Mach number (Breakdown Mach).",
      "OpenRocket's geometry warnings.",
    ],
    how: "OpenRocket's own aerodynamic model at zero angle of attack (Barrowman-based, with its transonic and supersonic corrections).",
    not: "Not CFD. For CFD drag and CP, export the geometry (Files group) and import the results with RASAero aero table.",
  },
  ballast: {
    needs: [MOTOR],
    gives: ["The nose mass that brings the minimum ascent stability (rail exit to apogee) to the target, its cost in apogee and rail exit speed, and whether the target can be reached from that position."],
    how: "A first guess from the static margin, refined by flying the design with the mass added.",
    not: "It does not add the mass. Add a Mass component on the Design page once agreed.",
    example: "If the minimum stability is 1.6 cal and the target 2.0 cal, it reports e.g. 180 g at 60 % of the nose length and the apogee that mass costs.",
  },
  roll_analysis: {
    needs: [MOTOR],
    gives: ["For each fin cant angle: peak roll rate, roll rate at burnout, peak angle of attack, and whether the roll rate crosses the pitch frequency (roll-pitch resonance)", "The fin alignment tolerance that keeps the roll under the limit."],
    example: "If 0.5° of cant gives 3.1 rev/s at burnout against a 2 rev/s limit, it recommends a tighter alignment tolerance, e.g. ±0.25°.",
  },
  compare_shapes: {
    needs: [MOTOR],
    gives: ["Apogee, change from now, Cd at the design Mach, minimum stability and mass for every nose profile (conical, ogive, elliptical, power series, parabolic, Von Kármán, LV-Haack), every fin edge (square, rounded, airfoil), and the best nose with the best fins."],
    not: "The design is not changed.",
  },
  recovery_analysis: {
    needs: [MOTOR, CHUTES, "Shear pins: a pin type from the team standards (e.g. 4-40 nylon, 140 N each) or a pin strength."],
    gives: [
      "For each deployment: airspeed (including sideways speed and wind), altitude, air density and mass.",
      "Opening load two ways: infinite mass (Cx × q × CdA) and a finite-mass inflation simulation; the design load per the team standards.",
      "Shear pins needed to keep later bays closed, steady descent rate and landing energy.",
    ],
    example: "Drogue opens at 35 m/s, ρ = 1.1 kg/m³, CdA = 0.12 m²: q = 674 Pa, load = 1.4 × 674 × 0.12 = 113 N.",
  },
  parachute_sizes: {
    needs: [MOTOR, "A parachute in the design, or a mass under it."],
    gives: ["A chart of descent rate against canopy diameter for up to three Cd values, with the rule limits, your target, the catalog parachutes and the one you have now", "The diameter each Cd needs. Apply sizes your parachute to it and re-flies."],
    example: "4.5 kg under a main, target 6 m/s at sea level: Cd 1.5 needs 1.30 m (51 in); Cd 0.8 needs 1.79 m (70 in).",
  },
  size_parachute: {
    needs: ["A target descent rate (*). Then either a parachute in the design (its mass is taken from the flight) or a mass."],
    gives: ["Required CdA and diameter, and the closest real parachutes from OpenRocket's catalog with their descent rate and packed size."],
    how: "Steady descent: drag = weight, so CdA = 2 m g ÷ (ρ v²).",
    example: "4.5 kg at 6 m/s, ρ = 1.225 kg/m³: CdA = 2 × 4.5 × 9.81 ÷ (1.225 × 36) = 2.0 m²; at Cd 1.5 that is 1.30 m diameter.",
  },
  deployment_delay_sweep: {
    needs: [MOTOR, CHUTES, "Which device to delay (*), and the delays (*)."],
    gives: ["Airspeed and opening load for each delay. With a pin count and pin rating: whether the pinned joint holds, and the latest delay that still keeps the load within the pins' capacity."],
    not: "The design's deployment settings are restored afterwards.",
    example: "Four 140 N pins with a safety factor of 2 hold 560 ÷ 2 = 280 N. If the drogue load is 110, 160, 230, 320 N at 0, 1, 2, 3 s late, up to 2 s is safe.",
  },
  recovery_sections: {
    needs: [CHUTES],
    gives: ["The sections that land separately (split at the parachute bays or at the joints you name), each with its landing mass, landing speed and kinetic energy against the limit, the energy if the main fails, and each bay's fill and a black powder estimate."],
  },
  shear_pins: {
    needs: ["The load the joint must hold (*), e.g. the drogue's opening load from Recovery loads, and a pin type or pin strength."],
    gives: ["Number of pins, and the force needed to break them (what the ejection charge must overcome)."],
    example: "Hold 300 N with a safety factor of 2 → 600 N; 4-40 nylon at 140 N each → 5 pins (700 N to break).",
  },
  ejection_charge: {
    needs: ["The bay: a body tube from the design, or its inner diameter and free length, or its volume (one of these, *).", "What to reach: a target pressure, or the shear pins (type and count) to break."],
    gives: ["Black powder mass for the primary and backup charges, the pressure and separating force they make, and the largest charge-well volume."],
    how: "Ideal gas: m = P V ÷ (R T), R = 266 in·lbf/lbm·°R, T = 3307 °R.",
    not: "A starting point, not a final charge: always ground test.",
    example: "98 mm × 250 mm bay (1.9 L) at 15 psi: m = 15 psi × 115 in³ ÷ (266 × 3307) = 0.0020 lb = 0.9 g; backup × 1.25 = 1.1 g.",
  },
  opening_shock: {
    needs: ["Mass under the canopy (*), airspeed at line stretch (*), Cd (*), and the canopy diameter or area."],
    gives: ["Opening load by the infinite-mass method (Cx × q × CdA), by a finite-mass inflation simulation, the Knacke mass ratio, and the steady drag."],
    not: "With a design, use Recovery loads instead: it takes the speed, altitude and mass from the flight.",
    example: "4.5 kg at 40 m/s, Cd 1.5, 1.42 m canopy (1.58 m²), sea level: q = 980 Pa, infinite-mass load = 1.4 × 980 × 2.37 = 3.3 kN.",
  },
  recovery_bay_fit: {
    needs: ["The items to pack (*): parachutes by packed size or catalog part, shock cords by length (and width × thickness), other hardware by volume.", "The bay: a part from the design, or its diameter and length or volume."],
    gives: ["Packed volume × packing factor against the bay, and the bay length needed."],
    example: "A 1.4 m canopy (0.9 L packed) and 9 m of 19 × 6 mm cord (1.0 L) × packing factor 2 = 3.8 L; a 98 mm tube needs 500 mm.",
  },
  search_parachutes: {
    needs: ["Nothing: optionally a diameter range or a maker."],
    gives: ["Parachutes in OpenRocket's parts database with diameter, Cd, CdA and packed size."],
  },
  descent_energy: {
    needs: ["Each separately landing section with its mass (*), and the descent rate (*)."],
    gives: ["Kinetic energy at touchdown for each section (½ m v²), and the fastest descent that keeps the heaviest section under the limit."],
    example: "A 3 kg section at 6 m/s: ½ × 3 × 36 = 54 J (40 ft-lbf), under a 102 J (75 ft-lbf) limit.",
  },
  fin_flutter: {
    needs: [MOTOR, "The fins' material and thickness in the design (the shear modulus comes from the material via the team standards), or a shear modulus here."],
    gives: [
      "Each fin set's flutter speed along the flight and the worst margin (flutter speed ÷ airspeed), with when and where it happens.",
      "The thickness, or the shear modulus, that would reach the team's margin (1.5 by default).",
    ],
    how: "NACA TN 4197 flutter estimate for a solid plate fin, from the local air pressure and speed of sound at each point of the flight.",
    not: "A screening estimate, not a clearance. Composite, sandwich and tip-to-tip fins need the laminate's real shear modulus; confirm with a stiffness test.",
    example: "OpenRocket's two-stage example (3.2 mm birch plywood, G = 0.6 GPa): flutter 68 m/s against 159 m/s flown, margin 0.43: FAIL. Reaching 1.5 needs 7.3 mm, or G = 7.4 GPa at the same thickness.",
  },
  fin_fea: {
    needs: [
      MOTOR,
      "The fin material's constants: Young's modulus, shear modulus, Poisson's ratio and allowable stress. They come from the team standards (Standards page, structures) unless you type them here.",
      "To get stresses and frequencies: CalculiX (free) installed. On a Mac: conda install -c conda-forge calculix; RocketMaxxing finds it in conda's folders. Without it the model is still written for you to run elsewhere.",
    ],
    gives: [
      "The load: the worst of a crosswind gust at max dynamic pressure and the largest q × angle of attack in the flight, as the normal force on the most loaded fin and the equivalent pressure.",
      "Tip deflection, peak stress against the allowable stress with the safety factor, and the first natural frequencies with their mode shape (bending or torsion).",
      "Hand estimates beside them (root bending stress 6 M ÷ (c t²), first bending frequency) and an input deck (.inp, also read by Abaqus) to refine.",
      "Optionally a picture of the fin coloured by stress (Plot path).",
    ],
    how: "One fin as a flat plate of 8-node shell elements, fully clamped along the root, loaded by a uniform pressure. Linear elastic, one material.",
    not: "Not an Ansys-style model of your build: no fin can, fillets, tabs, layup plies or body tube. A fully clamped root is stiffer than a surface-mounted fin, and a uniform pressure understates torsion. Use it to compare thicknesses and materials and to catch obvious failures, then test.",
    example: "OpenRocket's two-stage example (3.2 mm birch plywood): a 30 km/h gust at max q (Mach 0.47, q = 15 kPa) puts 56 N on one fin, 3.3 kPa over the fin; hand-estimate root stress 15.6 MPa × safety factor 2 = 31 MPa against 40 MPa allowable; first bending about 83 Hz.",
  },
  structural_loads: {
    needs: [MOTOR, "For a stress margin: the tube material's allowable stress."],
    gives: ["At every joint between airframe parts: the largest axial compression (boost and coast) and the bending moment at max dynamic pressure with a crosswind gust, the thin-wall tube stress, and the allowable stress needed with the safety factor.", "Optionally the load cases as CSV, to use in an FEA of couplers, fasteners and bulkheads."],
    how: "Quasi-static rigid-body loads with inertial relief, from the simulated flight.",
    not: "Not a stress analysis of the joint hardware itself: use the forces to size couplers, screws and bulkheads.",
  },
  aero_heating: {
    needs: [MOTOR],
    gives: ["Stagnation temperature at the nose tip and fin leading edges and recovery temperature on the body along the flight, against each part's material limit (e.g. epoxy glass transition), and the nose-tip heat flux and heat load."],
    how: "Adiabatic (upper-bound) temperatures and the Sutton-Graves heat flux.",
    not: "Not a transient thermal model. Below the limit is clear; above it needs a proper thermal check. It matters from about Mach 2.",
  },
  compare_materials: {
    needs: [MOTOR],
    gives: ["For each material (and thickness) of the fins, airframe or chosen parts: launch mass, apogee, ascent stability, rail exit, fin flutter margin, fin root bending, tube wall stress, shell buckling and heating. Options that pass every check come first, by apogee."],
    not: "The design is not changed. Densities are OpenRocket's; moduli and strengths are the team standards'.",
  },
  pressure_vessel: {
    needs: ["MEOP (*): the relief valve's set pressure. Then either the design burst pressure, or outer diameter + wall thickness + ultimate strength for an estimate."],
    gives: ["Proof pressure needed (≥ 1.5 × MEOP), burst margin (metal ≥ 2 × MEOP × weld knockdown; COPVs and chambers ≥ 4 × MEOP) per the Launch Canada 2027 edicts."],
    how: "Thin-wall Barlow estimate: burst = 2 × strength × wall ÷ outer diameter.",
    example: "6 in × 1/8 in 6061-T6 (42 ksi): burst = 2 × 42,000 × 0.125 ÷ 6 = 1,750 psi. At 800 psi MEOP the metal rule needs 1,600 psi: passes without welds.",
  },
  add_avionics_bay: {
    needs: ["Two body tubes in the same stage: the bay goes between them. Your real parts (name, mass, length) for accurate masses."],
    gives: ["Coupler and bulkheads, one altimeter circuit each with its own battery and switch, primary and backup charges on each bulkhead, U-bolts and the GPS tracker, all as real parts. The bay mass and static port sizes."],
    not: "It changes the design (undo reverts it).",
  },
  weather_forecast: {
    needs: ["The launch site's latitude and longitude (here, or in the team standards) and the launch time. Internet access, or the forecast JSON pasted."],
    gives: ["Ground wind, gusts, temperature and pressure, and wind at each altitude up to the jet stream, from Open-Meteo. With Apply, the simulation flies it."],
    example: "Latitude 32.99, longitude -106.97, time 2027-06-20T09:00.",
  },
  rail_buttons: {
    needs: [MOTOR],
    gives: ["Positions for both buttons, the real rail exit speed (the rocket is guided only until the aft button leaves), tip-off rate and angle, slop, side loads, and the change from now. Apply moves them."],
    example: "An aft button 300 mm above the aft end wastes 300 mm of rail; moving it to 30 mm gives the rocket 270 mm more guided travel and a faster rail exit.",
  },
  flight_card: {
    needs: [MOTOR, "For launch day, the day's winds first (Launch-day forecast)."],
    gives: ["A one-page Markdown card: vehicle, stability, motor and its best delay, predicted flight, recovery settings, landing energy, drift for each wind, the rule check and sign-off lines."],
  },
  altimeter_settings: {
    needs: [MOTOR, CHUTES],
    gives: ["Primary and backup settings for each device, the airspeed the backup drogue fires at, the Mach lockout time, and the static port size for the bay. Optionally a card with a pre-flight checklist."],
  },
  sensor_check: {
    needs: [MOTOR, "The flight computer's sensors (team standards, electronics.sensors) or the list here."],
    gives: ["Peak acceleration against each accelerometer's range, body rates against the gyroscope, the lowest pressure against the barometer, GPS against its 515 m/s and 18 km limits, and when the barometer cannot be trusted near Mach 1."],
    example: "If boost peaks at 22 g, a ±16 g accelerometer saturates for part of the burn and the flight software must not rely on it then.",
  },
  sensor_data: {
    needs: [MOTOR, "The sensors (team standards or the list here)."],
    gives: ["A CSV of what each sensor would read through the flight, with noise, resolution and range limits, plus the true values and the true event times, to test launch, burnout, apogee and main detection on the bench."],
  },
  radio_link: {
    needs: [MOTOR, "The radio: frequency, transmit power, antenna gains and receiver sensitivity (team standards or here)."],
    gives: ["Received power against sensitivity at the farthest point, at apogee and after landing (antenna in the grass, usually the limit), and the longest ground range that keeps the margin."],
    example: "At 9 km, the free-space loss at 915 MHz is 111 dB: 20 dBm + 2 dBi + 7 dBi - 111 dB = -82 dBm, 38 dB above a -120 dBm receiver. On the ground the loss is far larger, so after landing is usually the limit.",
  },
  power_budget: {
    needs: [MOTOR, "The battery circuits and e-match data (team standards or here)."],
    gives: ["Each battery's capacity used over pad wait + flight + search, the current each e-match gets, and the voltage while a charge fires against the electronics' brownout voltage."],
  },
  wind_profile: {
    needs: ["Mode (*): levels from a forecast or sounding, a power law from the ground wind, average to go back to one wind, or show."],
    gives: ["Wind by altitude on the simulation; every analysis then flies it."],
    how: "Power law: v = v_ground × (h ÷ 10 m)^α, α ≈ 0.14 over open land.",
    example: "20 km/h at 10 m with α = 0.14 gives 27 km/h at 100 m and 38 km/h at 1,000 m.",
  },
  monte_carlo: {
    needs: [MOTOR],
    gives: ["Apogee spread, landing ellipse and distances per stage, worst stability and rail exit (and how many runs break the rules), worst deployment airspeed and opening load, and which inputs drive the spread. Optionally a landing map and a Google Earth file."],
    how: "Many flights with wind speed and direction, launch angle and direction and turbulence varied at random (and optionally mass, drag, thrust and parachute Cd), in parallel.",
    example: "200 runs with wind 20 ± 7 km/h from any direction give the apogee spread (e.g. 3,050 ± 60 m) and the radius that holds 95 % of landings.",
  },
  simulate_engine: {
    needs: [
      "The engine type (*), the nozzle's throat (*) and exit diameters, and c* (and gamma) for your propellants from a thermochemistry code (NASA CEA, RocketCEA, ProPEP) at your chamber pressure. For a hybrid or a liquid, a table of c* against mixture ratio is better than one value.",
      "Solid (under More options): number of BATES grains, their outer, core and length, propellant density, and the burn rate a and n in r = a Pc^n.",
      "Hybrid: oxidizer mass; the injector (hole diameter and count, discharge coefficient, oxidizer density, tank pressure at the start and when the liquid runs out) or a known oxidizer flow; the fuel grain (port and outer diameter, length, density) and its regression a and n in r = a Gox^n.",
      "Liquid: oxidizer and fuel mass, chamber pressure, and for a blowdown feed the tanks' gas fraction at the start.",
    ],
    gives: [
      "Total impulse and class, burn time, thrust at ignition, peak and end, delivered specific impulse, chamber pressure, mass flow and mixture ratio through the burn, what is left unburnt, the nozzle's exit pressure against the outside air, and warnings (flow separation, a port that floods, an injector pressure drop that invites chugging, erosive burning).",
      "With a name, maker, diameter, length and dry mass: a motor in the motor list, whose mass and centre of gravity follow the burn as modelled. With a design open that has a motor mount, the thrust follows the air pressure along that rocket's own flight.",
    ],
    how: "A lumped model: at each moment the chamber pressure is the one at which the throat passes what the propellant feeds in, Pc = flow x c* / throat area, and thrust = Cf x Pc x throat area for an ideal nozzle with an efficiency.",
    not: "A prediction, not a measurement: ignition, tail-off, erosive burning, throat erosion and the two-phase flow of nitrous oxide are left out. Expect the first firing to differ by several percent, then make the motor from its log (Motors page, Your own motor, raw test-stand log). The design is not changed.",
    example: "Hybrid: 22 mm throat, 45 mm exit, 6 kg of nitrous oxide at 50 bar through 12 holes of 1.5 mm (Cd 0.4), paraffin grain 30 mm port in 90 mm x 500 mm, r = 0.155 Gox^0.5: about 14,700 N s (N class) over 13 s, 24 bar in the chamber at ignition.",
  },
  rank_motors: {
    needs: ["A motor mount (*). No motor needed: each candidate is flown."],
    gives: ["Every motor that fits the mount's diameter and length, flown and ranked: apogee, rail exit, thrust-to-weight, minimum stability with that motor's mass, max Mach, best delay, and whether it meets the rules."],
    not: "The design is not changed; pick a motor on the Motors page.",
  },
  sweep: {
    needs: [MOTOR, "The parameter (*) and its values (*); for a part's property, the part."],
    gives: ["A table (and plot) of apogee, stability, rail exit and more for each value."],
    not: "Part values are restored afterwards.",
    example: "Parameter height with Component = the fins and values 100 mm, 125 mm, 150 mm flies three fin spans and shows how stability rises and apogee falls with span.",
  },
  optimize_fins: {
    needs: [MOTOR, "A trapezoidal fin set."],
    gives: ["The best root chord, tip chord, span, sweep (and stock thickness from your list) for the goal, meeting stability, rail exit and flutter margin; runners-up and a side-by-side drawing. Apply changes the fins."],
    not: "Planform only. For nose and fin edge profiles use Nose and fin shapes; check the result's strength with Fin stress (FEA).",
  },
  optimize: {
    needs: [MOTOR, "1 to 3 variables (*): a part, a property and a min–max range; and the goal (*)."],
    gives: ["The best values found while meeting the constraints, with the flight they give. Apply changes the design."],
    example: "Ballast mass 0–500 g for a 3,048 m (10,000 ft) target apogee at ≥ 1.5 cal.",
  },
  add_from_link: {
    needs: ["A product page link, or its text pasted when the shop cannot be reached."],
    gives: ["The part in the design (parachute, motor, catalog part or electronics) with where each value came from (page, catalog, given, assumed), and the effect on the flight."],
    not: "It changes the design (undo reverts it) unless Apply is No.",
  },
  compare_designs: {
    needs: ["One baseline (*): another open design, an .ork file, or a git revision of this file."],
    gives: ["Changes in length, mass, CG, CP, stability, motors and flight, every rule check whose status changed, and the part edits."],
  },
  progress_log: {
    needs: ["Nothing: it records today's state next to the design file."],
    gives: ["What improved, got worse, was fixed or is new since the last record or a milestone, written to paste into a team channel."],
  },
  history: {
    needs: ["Nothing."],
    gives: ["This session's edits that can be undone and redone, and whether there are unsaved changes."],
  },
  import_aero_table: {
    needs: ["A RASAero II \"export aero data\" CSV, or any CSV with Mach, CD (and CP)."],
    gives: ["The table's drag replaces OpenRocket's in every simulation and its CP is used for the stability check; shown next to OpenRocket's own, with the effect on apogee and stability."],
  },
  compare_flight: {
    needs: [MOTOR, "An altimeter log (*): CSV with time and altitude."],
    gives: ["Apogee, time to apogee and descent rates, measured against simulated, and the drag multiplier that reproduces the measured apogee. Optionally an overlay plot."],
  },
  generate_report: {
    needs: [MOTOR, "An output folder (*)."],
    gives: ["report.md with the rule checks, stability, motors, flight and recovery, the two stability plots Launch Canada asks for, and flight-data.csv."],
  },
  export_flight_data: {
    needs: [MOTOR, "A file name (*)."],
    gives: ["The flight's full time series as CSV, for a spreadsheet, MATLAB or Python."],
  },
  export_geometry: {
    needs: [MOTOR],
    gives: ["STL of the outer shape (a named region per part) for CFD meshers and CAD, DXF flat patterns of the fins, the CFD run matrix (Mach, altitude, air state, Reynolds number), and the results template that RASAero aero table reads back."],
    not: "It does not run CFD: it prepares the inputs for OpenFOAM, SimScale, Ansys Fluent or STAR-CCM+.",
  },
  mass_budget: {
    needs: ["Nothing to start: it writes the model's breakdown as a CSV to fill in. Then the filled budget (CSV or list)."],
    gives: ["Budget against model part by part, projected dry and launch mass, contingency and margin to the target. Apply writes the masses into the design."],
  },
};

/** Analyses that read the simulated flight, so need a motor. */
export function needsFlight(tool: string): boolean {
  return GUIDES[tool]?.needs.includes(MOTOR) ?? false;
}
