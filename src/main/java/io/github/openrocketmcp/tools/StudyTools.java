package io.github.openrocketmcp.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Diff;
import io.github.openrocketmcp.or.Heating;
import io.github.openrocketmcp.or.LoadDiagrams;
import io.github.openrocketmcp.or.Loads;
import io.github.openrocketmcp.or.MaterialStudy;
import io.github.openrocketmcp.or.Requirements;
import io.github.openrocketmcp.or.Roll;
import io.github.openrocketmcp.or.Sections;
import io.github.openrocketmcp.or.Shapes;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** Design studies: aerodynamic shape trade, recovery sections, structural loads. */
public final class StudyTools {
	private StudyTools() {
	}

	public static void register(McpServer s, Context ctx) {
		registerMore(s, ctx);
		s.tool(new ToolDef("compare_shapes", "Nose cone and fin shape trade study",
				"Fly the design with every nose cone profile (conical, tangent ogive, elliptical, 1/2 and 3/4 power, parabolic, "
						+ "1/2 parabola, Von Karman, LV-Haack; optionally at several nose lengths) and every fin edge profile "
						+ "(square, rounded, airfoil), then the best nose with the best fins. Returns apogee, change vs current, CD "
						+ "at the design Mach, minimum ascent stability, launch mass, plus shape guidance. Does not edit the design.",
				SimTools.simSelect(Schema.object())
						.str("noseCone", "Nose cone id or name (default: the first nose cone).", false)
						.array("noseLengths", "Nose lengths to try (each with every profile), e.g. [\"16 in\", \"20 in\"].",
								Schema.quantityItem(), false)
						.bool("fins", "Also compare fin edge profiles (default true).", false)
						.num("designMach", "Mach number for the CD column (default: the current design's simulated max Mach).", false)
						.num("minStability", "Flag options below this minimum ascent stability (default: the rule-set floor).", false)
						.build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Simulation base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(),
							ctx.standards(), false);
					NoseCone nose;
					if (a.has("noseCone")) {
						nose = Components.find(base.getRocket(), a.str("noseCone"), NoseCone.class, "nose cone");
					} else {
						nose = null;
						for (RocketComponent c : base.getRocket()) {
							if (c instanceof NoseCone nc) {
								nose = nc;
								break;
							}
						}
						if (nose == null) {
							throw new ToolException("The design has no nose cone.");
						}
					}
					double minStab = a.num("minStability", Double.NaN);
					if (Double.isNaN(minStab)) {
						double f = Requirements.stabilityFloor(ctx.standards(),
								base.getRocket().getFlightConfiguration(base.getFlightConfigurationId()))[0];
						minStab = f > 0 ? f : Double.NaN;
					}
					return Shapes.study(base, d.doc, nose, a.has("noseLengths") ? a.qtyList("noseLengths", Dim.LENGTH) : null,
							a.bool("fins", true), a.num("designMach", Double.NaN), minStab);
				}));

		s.tool(new ToolDef("compare_materials", "Material trade study",
				"Simulate building parts from different materials: the fins, the airframe (nose cone, body tubes and "
						+ "transitions together, or each group), or named components, each candidate material (and optionally each stock "
						+ "thickness) flown in OpenRocket on a copy of the design. For every option: launch mass, apogee, ascent stability "
						+ "and rail exit, plus the material checks - fin flutter margin (shear modulus), fin root bending (strength), wall "
						+ "stress of tubes, transitions and the nose cone at their joints (strength), shell buckling (Young's modulus: "
						+ "cylinders and cones), and aerodynamic heating against the service temperature. Options that pass every check "
						+ "come first, by apogee. Densities are OpenRocket's; moduli, strengths and temperatures are the team standards' "
						+ "(structures.*) with sources. Weighed (mass-overridden) parts change by the computed difference. Does not "
						+ "edit the design; use edit_components to apply a choice.",
				SimTools.simSelect(Schema.object())
						.array("parts", "What to build from each material: \"fins\", \"airframe\" (nose cone, body tubes and transitions), "
								+ "\"body tubes\", \"nose cone\", \"transitions\" (boattail, shoulders), or component ids / names (default "
								+ "[\"fins\"]).", Schema.type("string", null), false)
						.array("materials", "OpenRocket material names to compare, e.g. [\"Fiberglass\", \"Carbon fiber\", \"Aluminum\"] "
								+ "(default: common choices for those parts). The current material is always included.",
								Schema.type("string", null), false)
						.array("thicknesses", "Stock thicknesses to try with every material (fin sheet, tube wall or nose shell), e.g. "
								+ "[\"1/8 in\", \"3/16 in\"] (default: as designed).", Schema.quantityItem(), false)
						.num("minStability", "Flag options below this minimum ascent stability (default: the rule-set floor).", false)
						.build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Simulation base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(),
							ctx.standards(), false);
					MaterialStudy.Selection sel = MaterialStudy.select(base.getRocket(),
							a.has("parts") && !a.strList("parts").isEmpty() ? a.strList("parts") : List.of("fins"));
					double minStab = a.num("minStability", Double.NaN);
					if (Double.isNaN(minStab)) {
						double f = Requirements.stabilityFloor(ctx.standards(),
								base.getRocket().getFlightConfiguration(base.getFlightConfigurationId()))[0];
						minStab = f > 0 ? f : Double.NaN;
					}
					return MaterialStudy.study(base, d.doc, ctx.standards(), sel, a.has("materials") ? a.strList("materials") : null,
							a.has("thicknesses") ? a.qtyList("thicknesses", Dim.LENGTH) : null, minStab);
				}));

		s.tool(new ToolDef("recovery_sections", "Recovery sections from the design",
				"Split the airframe into independently landing, tethered sections (automatically at the bays holding recovery "
						+ "devices, or at the joints you name) and report each section's landing mass (components plus burnt-out "
						+ "motor cases), simulated landing velocity and kinetic energy against the limit, the energy if the main "
						+ "fails, and each bay's packed recovery volume, free volume, fill and a black powder estimate.",
				SimTools.simSelect(Schema.object())
						.array("joints", "Airframe pieces (names or ids) whose FORWARD end separates; default automatic.",
								Schema.type("string", null), false).build(),
				false, a -> {
					Simulation sim = SimTools.runSelected(ctx, a);
					Set<String> joints = a.has("joints") ? new LinkedHashSet<>(a.strList("joints")) : null;
					return Sections.analyze(sim, ctx.standards(), joints);
				}));

		s.tool(new ToolDef("structural_loads", "Flight loads at the airframe joints",
				"Quasi-static rigid-body loads at every joint between airframe pieces: the largest axial compression during "
						+ "boost and coast, and the bending moment at maximum dynamic pressure with a crosswind gust (and with the "
						+ "simulated angle of attack), with inertial relief. Converts them to thin-wall tube stress and the required "
						+ "allowable with the team's safety factor. Use the forces to size couplers, fasteners and bulkheads.",
				SimTools.overrides(SimTools.simSelect(Schema.object()))
						.qty("gustSpeed", "Crosswind gust at max q (default: the rule set's maximum ground wind, 30 km/h).", false)
						.num("safetyFactor", "Safety factor on stress (default structures.loadSafetyFactor, 2).", false)
						.qty("allowableStress", "Allowable stress of the tube material, e.g. \"200 MPa\", for a margin.", false)
						.str("csvPath", "Also write the load cases per joint (SI CSV: station, axial force, bending moments, "
								+ "times, Mach, angle of attack) for FEA of tubes, couplers and fasteners.", false).build(),
				true, a -> {
					Simulation sim = SimTools.runSelected(ctx, a);
					double gust = a.qty("gustSpeed", Dim.VELOCITY,
							orDefault(ctx.standards().rule("maxGroundWind.value", Dim.VELOCITY), 30 / 3.6));
					double sf = a.num("safetyFactor", ctx.standards().q("structures.loadSafetyFactor", Dim.DIMENSIONLESS, 2));
					StringBuilder csv = a.has("csvPath") ? new StringBuilder() : null;
					Map<String, Object> out = Loads.analyze(sim, gust, sf, a.qtyOrNaN("allowableStress", Dim.PRESSURE), csv);
					if (csv != null) {
						Path p = ctx.path(a.str("csvPath"));
						if (p.getParent() != null) {
							Files.createDirectories(p.getParent());
						}
						Files.writeString(p, csv.toString());
						out.put("csv", p.toAbsolutePath().toString());
					}
					return out;
				}));
	}

	static void registerMore(McpServer s, Context ctx) {
		s.tool(new ToolDef("load_diagrams", "Load, shear and bending moment diagrams (UTAT method)",
				"Transverse load, shear, bending moment and axial force along the rocket at max q, by the University of Toronto "
						+ "Aerospace Team's load calculation (Load_calculations.py, github.com/ArthurJWH/UTAT-Rocketry, by Fred Chun, edited "
						+ "by Nat Espinosa, Alexander Boldis and Nadim Hatoum): the vehicle at a set angle of attack (default 5 degrees), "
						+ "each lifting part's lift at its centre of pressure, the parts' inertia answering as a rigid body. Parts, masses, "
						+ "lift slopes and CPs come from the design and the flight state from its simulation; or give the team's own "
						+ "files (parts .dat, OpenRocket conditions .dat, AeroLab CP, lift-slope and CD tables) to run them as the script "
						+ "does. Returns the maxima, values at every joint and the stations to plot.",
				SimTools.simSelect(Schema.object())
						.qty("angleOfAttack", "Angle of attack at max q (default 5 deg, as the team's script).", false)
						.bool("withThrust", "Include the motor's thrust at max q in the axial force (default false: coast, as the script).", false)
						.str("partsFile", "The team's parts file (.dat: Length, Reference Area, Rocket_Part, mass_m, x_cm; cm and kg).", false)
						.str("conditionsFile", "With partsFile: the OpenRocket conditions file (.dat: rho_inf, velocity_v_inf, gust, T_inf, cg_x).", false)
						.str("cpFile", "AeroLab centres of pressure by Mach (.DAT, cm).", false)
						.str("clFile", "AeroLab lift-curve slopes by Mach (.DAT, per rad).", false)
						.str("cdFile", "AeroLab drag coefficient by Mach (.dat).", false)
						.array("joints", "With the files: joints to report, [{name, x}] with x from the nose tip (\"1.01 m\", or metres).",
								Schema.object().str("name", "Joint, e.g. \"Rec Bay/UEB Joint\".", true).qty("x", "Station from the nose tip.", true).item(), false)
						.str("csvPath", "Also write the stations as CSV (SI: station, transverse load, shear, bending moment, axial force).", false)
						.build(),
				true, a -> {
					double alpha = a.has("angleOfAttack") ? a.qty("angleOfAttack", Dim.ANGLE) : Math.toRadians(5);
					LoadDiagrams.Input in;
					String source;
					if (a.has("partsFile")) {
						for (String k : List.of("conditionsFile", "cpFile", "clFile", "cdFile")) {
							if (!a.has(k)) {
								throw new ToolException("With partsFile, give " + k + " too (the script needs all five files).");
							}
						}
						List<LoadDiagrams.Joint> joints = new java.util.ArrayList<>();
						if (a.has("joints")) {
							for (var j : a.objList("joints")) {
								joints.add(new LoadDiagrams.Joint(j.str("name"), j.qty("x", Dim.LENGTH)));
							}
						}
						in = LoadDiagrams.fromFiles(ctx.path(a.str("partsFile")), ctx.path(a.str("conditionsFile")), ctx.path(a.str("cpFile")),
								ctx.path(a.str("clFile")), ctx.path(a.str("cdFile")), alpha, joints);
						source = "From the team's files: parts " + a.str("partsFile") + ", conditions " + a.str("conditionsFile")
								+ ", AeroLab CP, lift slopes and CD at Mach " + Units.num(in.state().mach()) + ".";
					} else if (a.has("conditionsFile") || a.has("cpFile") || a.has("clFile") || a.has("cdFile")) {
						throw new ToolException("The AeroLab and conditions files go with partsFile; without it the design gives everything.");
					} else {
						Simulation sim = SimTools.runSelected(ctx, a);
						in = LoadDiagrams.fromSimulation(sim, alpha, a.bool("withThrust", false));
						source = "From the design: " + sim.getName() + ", max q at t=" + Units.num(in.state().time()) + " s; parts are the "
								+ "airframe pieces with everything inside them (motor at its mass then), lift slopes and CPs from "
								+ "OpenRocket at that Mach.";
					}
					StringBuilder csv = a.has("csvPath") ? new StringBuilder() : null;
					Map<String, Object> out = LoadDiagrams.report(LoadDiagrams.compute(in), in, source, csv);
					if (csv != null) {
						Path p = ctx.path(a.str("csvPath"));
						if (p.getParent() != null) {
							Files.createDirectories(p.getParent());
						}
						Files.writeString(p, csv.toString());
						out.put("csv", p.toAbsolutePath().toString());
					}
					return out;
				}));

		s.tool(new ToolDef("compare_designs", "Design diff between two revisions",
				"Compare the design under review with a baseline - another open design, another .ork file, or an earlier git "
						+ "revision of the same file (e.g. \"HEAD~1\", a tag like \"PDR\", a branch). Both are flown in the same "
						+ "conditions. Returns the change in length, mass, CG, CP, static stability, motors, apogee, velocity, Mach, "
						+ "rail exit velocity, flight stability, descent rates, landing distance, every rule check whose status "
						+ "changed, and the component edits (added / removed / changed properties). Optionally writes a Markdown "
						+ "change summary for the design review. Does not edit either design.",
				SimTools.simSelect(Schema.object())
						.str("baselineDesignId", "An open design to compare against.", false)
						.str("baselinePath", "An .ork file to compare against (e.g. the version submitted at the last review).", false)
						.str("revision", "A git revision of the design's own file to compare against: commit, tag, branch, HEAD~1.", false)
						.bool("sameConditions", "Fly the baseline in the current design's simulation conditions (default true).", false)
						.str("path", "Also write the comparison as Markdown here, e.g. \"reviews/changes-since-pdr.md\".", false)
						.build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					int given = (a.has("baselineDesignId") ? 1 : 0) + (a.has("baselinePath") ? 1 : 0) + (a.has("revision") ? 1 : 0);
					if (given != 1) {
						throw new ToolException("Give exactly one baseline: baselineDesignId, baselinePath or revision.");
					}
					Designs.Design base;
					String label;
					if (a.has("baselineDesignId")) {
						base = ctx.designs.get(a.str("baselineDesignId"));
						label = base.id;
					} else if (a.has("baselinePath")) {
						Path p = ctx.path(a.str("baselinePath"));
						base = Diff.load(p, "file");
						label = p.getFileName().toString();
					} else {
						base = Diff.loadRevision(d, a.str("revision"));
						label = a.str("revision");
					}
					String current = d.path != null ? d.path.getFileName().toString() : d.id;
					if (current.equals(label)) {
						current = "current";
					}
					return Diff.compare(base, d, label, current, a.str("simulation", null),
							a.str("configuration", null), a.bool("sameConditions", true), ctx.standards(),
							a.has("path") ? ctx.path(a.str("path")) : null);
				}));

		s.tool(new ToolDef("aero_heating", "Aerodynamic heating screen",
				"Stagnation temperature at the nose tip and fin leading edges and recovery temperature on the body along the "
						+ "simulated flight, compared with each part's material limit (structures.maxServiceTemperature, e.g. epoxy "
						+ "glass transition, PLA softening), plus the nose-tip heat flux and heat load (Sutton-Graves). Adiabatic "
						+ "upper bounds: below the limit is clear; above it needs a thermal check. Matters from about Mach 2.",
				SimTools.overrides(SimTools.simSelect(Schema.object()))
						.qty("noseTipRadius", "Nose tip radius for the heat flux (default 5 mm).", false).build(),
				true, a -> {
					Simulation sim = SimTools.runSelected(ctx, a);
					return Heating.analyze(sim, ctx.standards(), a.qty("noseTipRadius", Dim.LENGTH, 0.005));
				}));

		s.tool(new ToolDef("roll_analysis", "Roll from fin misalignment",
				"Fly the design with several fin cant (misalignment) angles and report the maximum roll rate, the roll rate at "
						+ "burnout, the maximum angle of attack, and whether the roll rate crosses the vehicle's pitch natural "
						+ "frequency (roll-pitch resonance risk). Recommends the fin alignment tolerance that keeps the roll below "
						+ "maxRollRate with no crossing. Does not edit the design.",
				SimTools.simSelect(Schema.object())
						.array("cantAngles", "Cant angles to try (default 0, 0.1, 0.25, 0.5, 1, 2 deg).", Schema.quantityItem(), false)
						.str("finSet", "Only cant this fin set (id or name); default all fin sets.", false)
						.num("maxRollRate", "Acceptable roll rate in rev/s (default 2).", false).build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Simulation base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(),
							ctx.standards(), false);
					List<Double> cants = a.has("cantAngles") ? a.qtyList("cantAngles", Dim.ANGLE)
							: List.of(0.0, Math.toRadians(0.1), Math.toRadians(0.25), Math.toRadians(0.5), Math.toRadians(1),
									Math.toRadians(2));
					String finId = null;
					int finCount = 4;
					if (a.has("finSet")) {
						var f = Components.find(base.getRocket(), a.str("finSet"), FinSet.class, "fin set");
						finId = f.getID().toString();
						finCount = f.getFinCount();
					} else {
						for (RocketComponent c : base.getRocket()) {
							if (c instanceof FinSet f) {
								finCount = f.getFinCount();
							}
						}
					}
					return Roll.render(Roll.sweep(base, d.doc, finId, cants),
							a.num("maxRollRate", 2), finCount);
				}));
	}

	static double orDefault(double v, double d) {
		return Double.isNaN(v) ? d : v;
	}

}
