package io.github.openrocketmcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightDataType;

import io.github.openrocketmcp.calc.Atmosphere;
import io.github.openrocketmcp.calc.Nozzle;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Branch;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.EngineSim;
import io.github.openrocketmcp.or.Motors;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Variants;
import io.github.openrocketmcp.report.Png;
import io.github.openrocketmcp.report.Svg;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** An engine predicted from its design numbers (solid, hybrid or liquid), and made into a motor to fly. */
public final class EngineTools {
	private EngineTools() {
	}

	private static final double PSI = 6894.757293168;

	public static void register(McpServer s, Context ctx) {
		JsonObject table = Schema.type("array", "c* against mixture ratio, from a thermochemistry code at the chamber pressure: "
				+ "[[oxidizer/fuel, c* in m/s], ...] (or rows as text, \"3:1450\"). Used for hybrids (the ratio shifts through the "
				+ "burn) and liquids.");
		table.add("items", new JsonObject()); // a row is a pair of numbers, or the pair as text

		s.tool(new ToolDef("simulate_engine", "Predict an engine's burn from its design (solid, hybrid, liquid)",
				"Chamber pressure, mass flow and thrust through the burn of an engine that has not been fired yet, from its "
						+ "nozzle, its propellant and c* and gamma from a thermochemistry code (NASA CEA, RocketCEA, ProPEP). "
						+ "solid: BATES grains burning at r = a Pc^n. hybrid: oxidizer through an injector (or at a given flow) "
						+ "and a fuel port regressing at r = a Gox^n, with the mixture ratio shifting. liquid: a fixed mixture "
						+ "ratio, regulated or blowdown. Give designation, manufacturer, diameter, length and dryMass to make it "
						+ "a motor (an .rse that carries the real mass and CG through the burn) for set_motor. With designId the "
						+ "thrust follows the air pressure along that rocket's own flight. A lumped model: expect the first "
						+ "firing to differ by several percent, then use its log (create_custom_motor staticFireFile).",
				Schema.object()
						.enumStr("type", "Engine type.", true, "solid", "hybrid", "liquid")
						.qty("throatDiameter", "Nozzle throat diameter.", true)
						.qty("exitDiameter", "Nozzle exit diameter (or give expansionRatio).", false)
						.num("expansionRatio", "Exit area / throat area (or give exitDiameter).", false)
						.qty("cstar", "Characteristic velocity c* of the propellants, e.g. \"1550 m/s\" (or cstarTable).", false)
						.prop("cstarTable", table, false)
						.num("gamma", "Ratio of specific heats of the combustion gas (default 1.2).", false)
						.num("cstarEfficiency", "Share of the ideal c* the chamber delivers (default 0.95; hybrids 0.90).", false)
						.num("nozzleEfficiency", "Nozzle efficiency on the momentum thrust (default 0.97).", false)
						.qty("altitude", "Altitude above sea level the thrust is worked out for (default: the launch site's). "
								+ "Not used with designId.", false)
						.str("designId", "A design: the thrust then follows the air pressure along this rocket's flight.", false)
						.str("mount", "With designId: the motor mount (default: the first one).", false)
						// solid
						.integer("grainCount", "Solid: number of BATES grains.", false)
						.qty("grainOuterDiameter", "Solid and hybrid: outer diameter of the grain.", false)
						.qty("grainCoreDiameter", "Solid: core diameter.", false)
						.qty("grainLength", "Solid: length of one grain. Hybrid: length of the fuel grain.", false)
						.bool("inhibitedEnds", "Solid: grain ends do not burn (default false: BATES).", false)
						.qty("propellantDensity", "Solid: propellant density, e.g. \"1750 kg/m3\".", false)
						.num("burnRateCoefficient", "Solid: a in r = a Pc^n.", false)
						.num("burnRateExponent", "Solid: n in r = a Pc^n.", false)
						.enumStr("burnRateUnits", "Units of a: r in mm/s with Pc in MPa (default), or r in in/s with Pc in psi.", false,
								"mm/s-MPa", "in/s-psi")
						// hybrid and liquid
						.qty("oxidizerMass", "Hybrid and liquid: oxidizer loaded.", false)
						.num("oxidizerFlow", "Hybrid: oxidizer flow in kg/s, when it is known or regulated (else the injector).", false)
						.qty("injectorHoleDiameter", "Hybrid: diameter of one injector hole.", false)
						.integer("injectorHoleCount", "Hybrid: number of injector holes.", false)
						.num("dischargeCoefficient", "Hybrid: injector discharge coefficient (default 0.6; lower for nitrous oxide).", false)
						.qty("oxidizerDensity", "Hybrid: liquid oxidizer density (nitrous oxide about 750 kg/m3 at 20 C, LOX 1141).", false)
						.qty("tankPressure", "Hybrid: oxidizer tank pressure at the start.", false)
						.qty("tankPressureEnd", "Hybrid: tank pressure when the liquid runs out (default: unchanged).", false)
						.qty("portDiameter", "Hybrid: fuel port diameter at the start.", false)
						.integer("portCount", "Hybrid: number of ports (default 1).", false)
						.qty("fuelDensity", "Hybrid: fuel density (paraffin about 920 kg/m3, HTPB 930, ABS 1040).", false)
						.num("regressionCoefficient", "Hybrid: a in r = a Gox^n, r in mm/s and Gox in kg/m2/s.", false)
						.num("regressionExponent", "Hybrid: n in r = a Gox^n (usually 0.5 to 0.8).", false)
						.qty("fuelMass", "Liquid: fuel loaded.", false)
						.num("mixtureRatio", "Liquid: oxidizer / fuel by mass the engine runs at (default: what is loaded).", false)
						.qty("chamberPressure", "Liquid: chamber pressure (at the start, for blowdown).", false)
						.enumStr("feed", "Liquid: regulated (constant pressure, default) or blowdown.", false, "regulated", "blowdown")
						.num("ullageFraction", "Liquid blowdown: gas share of the tanks' volume at the start, e.g. 0.3.", false)
						.num("polytropicExponent", "Liquid blowdown: exponent of the gas's expansion (default 1.2).", false)
						// the motor
						.str("designation", "To make a motor: its name, e.g. \"Maple-H1\".", false)
						.str("manufacturer", "To make a motor: the team's name.", false)
						.qty("diameter", "To make a motor: outer diameter of the engine (with its tanks).", false)
						.qty("length", "To make a motor: its length.", false)
						.qty("dryMass", "To make a motor: its mass without propellant.", false)
						.qty("dryCgFromTop", "CG of the empty engine from its top (default: half its length).", false)
						.qty("oxidizerCgFromTop", "CG of the oxidizer from the top (the tank's centre; default half the length).", false)
						.qty("fuelCgFromTop", "CG of the fuel or the solid propellant from the top (default half the length).", false)
						.str("saveTo", "Where to write the .rse (default: motors/<designation>.rse).", false)
						.str("plotPath", "Write the thrust and chamber pressure plot (SVG) here.", false).build(),
				false, a -> simulate(ctx, a)));
	}

	private static Map<String, Object> simulate(Context ctx, Args a) throws IOException {
		String type = a.str("type").toLowerCase(Locale.ROOT);
		double throat = a.qty("throatDiameter", Dim.LENGTH);
		double eps;
		if (a.has("expansionRatio")) {
			eps = a.num("expansionRatio");
		} else if (a.has("exitDiameter")) {
			double exit = a.qty("exitDiameter", Dim.LENGTH);
			if (exit < throat) {
				throw new ToolException("exitDiameter (" + Units.fmt(exit, Dim.LENGTH) + ") is smaller than throatDiameter ("
						+ Units.fmt(throat, Dim.LENGTH) + ").");
			}
			eps = exit * exit / (throat * throat);
		} else {
			throw new ToolException("Give the nozzle's exitDiameter or its expansionRatio.");
		}
		double gamma = a.num("gamma", 1.2);
		DoubleUnaryOperator cstar = cstar(a);
		double etaC = a.num("cstarEfficiency", type.equals("hybrid") ? 0.90 : 0.95), etaN = a.num("nozzleEfficiency", 0.97);

		// Where the thrust is worked out: at one altitude, or along a rocket's flight (found by flying it, twice over).
		Designs.Design d = a.has("designId") ? ctx.designs.get(a.str("designId")) : null;
		double siteAltitude = a.has("altitude") ? a.qty("altitude", Dim.DISTANCE)
				: finite(ctx.standards().q("launchSite.altitudeMsl", Dim.DISTANCE, 0), 0);
		DoubleUnaryOperator ambient = t -> EngineSim.pressureAt(siteAltitude);
		EngineSim.Burn burn = run(type, a, new EngineSim.Common(throat, eps, gamma, cstar, etaC, etaN, ambient));
		Map<String, Object> flight = null;
		String notFlown = d == null ? null : cannotFly(d, a);
		if (d != null && notFlown == null) {
			Flown first = fly(ctx, d, a, type, burn);
			EngineSim.Burn atPad = burn;
			burn = run(type, a, new EngineSim.Common(throat, eps, gamma, cstar, etaC, etaN, first.ambient()));
			Flown second = fly(ctx, d, a, type, burn);
			burn = run(type, a, new EngineSim.Common(throat, eps, gamma, cstar, etaC, etaN, second.ambient()));
			ambient = second.ambient();
			flight = new LinkedHashMap<>();
			flight.put("design", d.name());
			flight.put("altitudeAtBurnout", Units.fmt(second.burnoutAltitude(), Dim.DISTANCE) + " above the pad");
			flight.put("apogee", Units.fmt(second.apogee(), Dim.DISTANCE));
			flight.put("impulseGainedOverThePad", Units.fmt(burn.totalImpulse() - atPad.totalImpulse(), Dim.IMPULSE) + " ("
					+ Units.num(100 * (burn.totalImpulse() / atPad.totalImpulse() - 1)) + "%): thinner air as it climbs");
			flight.put("note", "The design is not changed: the engine was flown in it to find the altitude through the burn.");
		}

		double impulse = burn.totalImpulse(), used = burn.propellantUsed(), tb = burn.burnTime();
		EngineSim.Step start = burn.steps().get(0), end = burn.steps().get(burn.steps().size() - 1);
		double pa0 = ambient.applyAsDouble(0);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("engine", type);
		out.put("totalImpulse", Units.fmt(impulse, Dim.IMPULSE) + " (" + Motors.impulseClass(impulse) + " class)");
		out.put("burnTime", Units.fmt(tb, Dim.TIME) + " (ends when " + burn.ended() + ")");
		out.put("thrust", "average " + Units.fmt(impulse / tb, Dim.FORCE) + ", peak " + Units.fmt(burn.peakThrust(), Dim.FORCE)
				+ ", " + Units.fmt(start.thrust(), Dim.FORCE) + " at ignition, " + Units.fmt(end.thrust(), Dim.FORCE) + " at the end");
		out.put("specificImpulse", Units.num(impulse / (used * Atmosphere.G0)) + " s delivered (total impulse / weight of propellant burnt)");
		out.put("chamberPressure", Units.fmt(start.chamberPressure(), Dim.PRESSURE) + " at ignition, " + Units.fmt(burn.peakPressure(),
				Dim.PRESSURE) + " at most, " + Units.fmt(end.chamberPressure(), Dim.PRESSURE) + " at the end");
		out.put("massFlow", Units.num(start.flow()) + " kg/s at ignition, " + Units.num(end.flow()) + " kg/s at the end");
		Map<String, Object> prop = new LinkedHashMap<>();
		if (burn.oxidizerLoaded() > 0) {
			prop.put("oxidizer", Units.fmt(burn.oxidizerLoaded(), Dim.MASS) + " loaded, " + Units.fmt(end.oxidizerLeft(), Dim.MASS) + " left");
			prop.put("fuel", Units.fmt(burn.fuelLoaded(), Dim.MASS) + " loaded, " + Units.fmt(end.fuelLeft(), Dim.MASS) + " left");
			prop.put("mixtureRatio", Units.num(start.mixtureRatio()) + " at ignition, " + Units.num(end.mixtureRatio()) + " at the end");
		} else {
			prop.put("propellant", Units.fmt(burn.fuelLoaded(), Dim.MASS) + " loaded, " + Units.fmt(end.fuelLeft(), Dim.MASS) + " left");
		}
		out.put("propellant", prop);

		// The nozzle against the air it works in.
		double pe = burn.exitPressureRatio() * start.chamberPressure();
		Map<String, Object> nozzle = new LinkedHashMap<>();
		nozzle.put("expansionRatio", Units.num(eps) + " (exit Mach " + Units.num(Nozzle.exitMach(eps, gamma)) + ")");
		nozzle.put("exitPressure", Units.fmt(pe, Dim.PRESSURE) + " at ignition, against " + Units.fmt(pa0, Dim.PRESSURE) + " outside");
		nozzle.put("thrustCoefficient", Units.num(start.thrust() / (start.chamberPressure() * Math.PI * throat * throat / 4)));
		nozzle.put("bestExpansionRatioHere", Units.num(Nozzle.optimumAreaRatio(gamma, pa0 / start.chamberPressure()))
				+ " (expands exactly to the outside pressure at ignition)");
		out.put("nozzle", nozzle);
		List<String> notes = new ArrayList<>(burn.notes());
		if (pe < 0.4 * pa0) {
			notes.add("The nozzle expands to " + Units.num(pe / pa0) + " of the outside pressure at ignition: below about 0.4 the flow "
					+ "separates from the nozzle wall (rough, lower thrust than shown, side loads). A smaller exit suits this altitude.");
		}
		if (flight != null) {
			out.put("flight", flight);
		} else {
			notes.add("Thrust is worked out at " + Units.fmt(siteAltitude, Dim.DISTANCE) + " above sea level throughout. It rises as the "
					+ "rocket climbs" + (notFlown != null ? ". To follow " + d.name() + "'s own flight, " + notFlown + "."
							: ": give designId to follow a rocket's own flight."));
		}
		notes.add("c* " + (a.has("cstarTable") ? "from the table, at the mixture ratio of each moment" : Units.fmt(cstar.applyAsDouble(1),
				Dim.VELOCITY)) + " x " + Units.num(etaC) + " (combustion efficiency), gamma " + Units.num(gamma) + ", nozzle efficiency "
				+ Units.num(etaN) + ". A prediction: ignition and tail-off, erosive burning and throat erosion are not modelled.");
		out.put("notes", notes);

		// A few moments of the burn, for the table.
		List<Map<String, Object>> rows = new ArrayList<>();
		int n = burn.steps().size();
		for (int k = 0; k <= 8; k++) {
			EngineSim.Step st = burn.steps().get(Math.min(n - 1, (int) Math.round(k * (n - 1) / 8.0)));
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("time", Units.fmt(st.time(), Dim.TIME));
			r.put("thrust", Units.fmt(st.thrust(), Dim.FORCE));
			r.put("chamberPressure", Units.fmt(st.chamberPressure(), Dim.PRESSURE));
			r.put("massFlow", Units.num(st.flow()) + " kg/s");
			if (burn.oxidizerLoaded() > 0) {
				r.put("mixtureRatio", Units.num(st.mixtureRatio()));
			}
			rows.add(r);
		}
		out.put("throughTheBurn", rows);

		double[][] curve = burn.curve(250);
		String title = (a.has("designation") ? a.str("designation") : capital(type) + " engine") + ": predicted thrust";
		boolean imperial = Units.system() == io.github.openrocketmcp.units.UnitSystem.IMPERIAL;
		double[] shown = new double[curve[1].length];
		for (int i = 0; i < shown.length; i++) {
			shown[i] = imperial ? curve[1][i] / 4.4482216152605 : curve[1][i];
		}
		String svg = Svg.line(title, "Time (s)", "Thrust (" + (imperial ? "lbf" : "N") + ")", curve[0], shown, Double.NaN, null, List.of());
		if (a.has("plotPath")) {
			Path p = ctx.path(a.str("plotPath"));
			if (p.getParent() != null) {
				Files.createDirectories(p.getParent());
			}
			Files.writeString(p, svg);
			out.put("plot", p.toAbsolutePath().normalize().toString());
		}
		Png.attach(svg, title);

		if (a.has("designation")) {
			for (String need : new String[] { "manufacturer", "diameter", "length", "dryMass" }) {
				if (!a.has(need)) {
					throw new ToolException("To make the motor, also give " + need + " (with designation: manufacturer, diameter, "
							+ "length and dryMass).");
				}
			}
			ThrustCurveMotor m = motor(ctx, a, type, burn, curve, true);
			out.put("motor", Motors.describe(m));
			out.put("file", ctx.path(a.str("saveTo", "motors/" + safe(a.str("designation")) + ".rse")).toAbsolutePath().normalize().toString());
			out.put("motorNote", "Added to the motor list (set_motor). Its mass and centre of gravity follow the burn as modelled: "
					+ "the oxidizer and the fuel each leave from their own place.");
		} else {
			out.put("toFlyIt", "Give designation, manufacturer, diameter, length and dryMass to make it a motor.");
		}
		return out;
	}

	/** The burn of this engine type in this air. */
	private static EngineSim.Burn run(String type, Args a, EngineSim.Common c) {
		switch (type) {
			case "solid": {
				need(a, type, "grainCount", "grainOuterDiameter", "grainCoreDiameter", "grainLength", "propellantDensity",
						"burnRateCoefficient", "burnRateExponent");
				if (!a.has("cstar")) {
					throw new ToolException("A solid needs cstar (one value: its propellant does not change through the burn).");
				}
				double n = a.num("burnRateExponent"), coefficient = a.num("burnRateCoefficient");
				// r = a Pc^n with Pc in Pa and r in m/s.
				double aSi = a.str("burnRateUnits", "mm/s-MPa").equals("in/s-psi") ? coefficient * 0.0254 / Math.pow(PSI, n)
						: coefficient * 1e-3 / Math.pow(1e6, n);
				double outer = a.qty("grainOuterDiameter", Dim.LENGTH), core = a.qty("grainCoreDiameter", Dim.LENGTH);
				if (core >= outer) {
					throw new ToolException("grainCoreDiameter (" + Units.fmt(core, Dim.LENGTH) + ") must be smaller than grainOuterDiameter ("
							+ Units.fmt(outer, Dim.LENGTH) + ").");
				}
				return EngineSim.solid(c, new EngineSim.Solid(Math.max(1, a.integer("grainCount", 1)), outer, core,
						a.qty("grainLength", Dim.LENGTH), a.bool("inhibitedEnds", false), a.qty("propellantDensity", Dim.DENSITY), aSi, n));
			}
			case "hybrid": {
				need(a, type, "oxidizerMass", "portDiameter", "grainOuterDiameter", "grainLength", "fuelDensity", "regressionCoefficient",
						"regressionExponent");
				double flow = Double.NaN, cdA = Double.NaN, rho = Double.NaN, tank = Double.NaN, tankEnd = Double.NaN;
				if (a.has("oxidizerFlow")) {
					flow = a.num("oxidizerFlow");
				} else {
					need(a, "hybrid without oxidizerFlow", "injectorHoleDiameter", "injectorHoleCount", "oxidizerDensity", "tankPressure");
					double hole = a.qty("injectorHoleDiameter", Dim.LENGTH);
					cdA = a.num("dischargeCoefficient", 0.6) * Math.max(1, a.integer("injectorHoleCount", 1)) * Math.PI * hole * hole / 4;
					rho = a.qty("oxidizerDensity", Dim.DENSITY);
					tank = a.qty("tankPressure", Dim.PRESSURE);
					tankEnd = a.has("tankPressureEnd") ? a.qty("tankPressureEnd", Dim.PRESSURE) : tank;
				}
				double port = a.qty("portDiameter", Dim.LENGTH), outer = a.qty("grainOuterDiameter", Dim.LENGTH);
				if (port >= outer) {
					throw new ToolException("portDiameter (" + Units.fmt(port, Dim.LENGTH) + ") must be smaller than grainOuterDiameter ("
							+ Units.fmt(outer, Dim.LENGTH) + ").");
				}
				// r = a Gox^n with r in mm/s: to m/s.
				return EngineSim.hybrid(c, new EngineSim.Hybrid(a.qty("oxidizerMass", Dim.MASS), flow, cdA, rho, tank, tankEnd,
						Math.max(1, a.integer("portCount", 1)), port, outer, a.qty("grainLength", Dim.LENGTH), a.qty("fuelDensity", Dim.DENSITY),
						a.num("regressionCoefficient") * 1e-3, a.num("regressionExponent")));
			}
			case "liquid": {
				need(a, type, "oxidizerMass", "fuelMass", "chamberPressure");
				double ox = a.qty("oxidizerMass", Dim.MASS), fuel = a.qty("fuelMass", Dim.MASS);
				boolean blowdown = a.str("feed", "regulated").equals("blowdown");
				if (blowdown && !a.has("ullageFraction")) {
					throw new ToolException("A blowdown feed needs ullageFraction: the gas share of the tanks' volume at the start (e.g. 0.3).");
				}
				return EngineSim.liquid(c, new EngineSim.Liquid(ox, fuel, a.num("mixtureRatio", ox / fuel), a.qty("chamberPressure", Dim.PRESSURE),
						blowdown ? a.num("ullageFraction") : Double.NaN, a.num("polytropicExponent", 1.2)));
			}
			default:
				throw new ToolException("type is solid, hybrid or liquid.");
		}
	}

	private static void need(Args a, String what, String... names) {
		List<String> missing = new ArrayList<>();
		for (String n : names) {
			if (!a.has(n)) {
				missing.add(n);
			}
		}
		if (!missing.isEmpty()) {
			throw new ToolException("A " + what + " engine also needs: " + String.join(", ", missing) + ".");
		}
	}

	/** c* at a mixture ratio: one value, or the table read between its rows (held at its ends). */
	private static DoubleUnaryOperator cstar(Args a) {
		if (a.has("cstarTable")) {
			JsonArray arr = a.array("cstarTable");
			double[] of = new double[arr.size()], c = new double[arr.size()];
			for (int i = 0; i < arr.size(); i++) {
				JsonElement p = arr.get(i);
				try {
					if (p.isJsonArray() && p.getAsJsonArray().size() == 2) {
						of[i] = p.getAsJsonArray().get(0).getAsDouble();
						c[i] = p.getAsJsonArray().get(1).getAsDouble();
					} else {
						// As typed in a form: "3:1450" (also "3 1450" or "3=1450").
						String[] cells = p.getAsString().strip().split("\\s*[:=\\s]\\s*");
						if (cells.length != 2) {
							throw new NumberFormatException();
						}
						of[i] = Double.parseDouble(cells[0]);
						c[i] = Double.parseDouble(cells[1]);
					}
				} catch (RuntimeException e) {
					throw new ToolException("cstarTable rows are [mixture ratio, c* in m/s], or typed as 3:1450, 4:1540, 6:1620.");
				}
				if (!(c[i] > 200 && c[i] < 5000) || !(of[i] > 0) || (i > 0 && of[i] <= of[i - 1])) {
					throw new ToolException("cstarTable row " + (i + 1) + " [" + of[i] + ", " + c[i] + "]: mixture ratios must rise, and "
							+ "c* is in m/s (a few hundred to a few thousand).");
				}
			}
			if (of.length < 2) {
				throw new ToolException("cstarTable needs at least two rows; for one value give cstar.");
			}
			return x -> {
				if (!(x > of[0])) {
					return c[0];
				}
				for (int i = 1; i < of.length; i++) {
					if (x <= of[i]) {
						return c[i - 1] + (c[i] - c[i - 1]) * (x - of[i - 1]) / (of[i] - of[i - 1]);
					}
				}
				return c[c.length - 1];
			};
		}
		if (!a.has("cstar")) {
			throw new ToolException("Give cstar (e.g. \"1550 m/s\") or cstarTable: the propellants' characteristic velocity, from NASA "
					+ "CEA, RocketCEA or ProPEP at your chamber pressure.");
		}
		double v = a.qty("cstar", Dim.VELOCITY);
		return x -> v;
	}

	/** The burn as a motor: the curve, the propellant left and the CG at each point. */
	private static ThrustCurveMotor motor(Context ctx, Args a, String type, EngineSim.Burn burn, double[][] curve, boolean keep)
			throws IOException {
		double length = a.qty("length", Dim.LENGTH), dry = a.qty("dryMass", Dim.MASS);
		double xDry = cg(a, "dryCgFromTop", length), xOx = cg(a, "oxidizerCgFromTop", length), xFuel = cg(a, "fuelCgFromTop", length);
		int n = curve[0].length;
		double[] left = new double[n], cgs = new double[n];
		for (int i = 0; i < n; i++) {
			left[i] = curve[2][i] + curve[3][i];
			cgs[i] = (dry * xDry + curve[2][i] * xOx + curve[3][i] * xFuel) / (dry + left[i]);
		}
		String designation = a.str("designation");
		Path file = keep ? ctx.path(a.str("saveTo", "motors/" + safe(designation) + ".rse")) : null;
		return Motors.createCustom(new Motors.CustomMotor(a.str("manufacturer"), designation, type.equals("solid") ? "reload" : "hybrid",
				a.qty("diameter", Dim.LENGTH), length, dry + left[0], left[0], curve[0], curve[1], xDry, Double.NaN, null,
				"Predicted by simulate_engine (" + type + "): not from a firing."), file, left, cgs, keep);
	}

	private static double cg(Args a, String key, double length) {
		if (!a.has(key)) {
			return length / 2;
		}
		double v = a.qty(key, Dim.LENGTH);
		if (v > length) {
			throw new ToolException(key + " (" + Units.fmt(v, Dim.LENGTH) + ") is measured from the top of the engine and must lie "
					+ "within its " + Units.fmt(length, Dim.LENGTH) + " length.");
		}
		return v;
	}

	/** What flying the engine in the design gave: the air pressure through the burn. */
	private record Flown(DoubleUnaryOperator ambient, double burnoutAltitude, double apogee) {
	}

	/** What is missing to fly the engine in the design (as "do this"), or null when it can be flown. */
	private static String cannotFly(Designs.Design d, Args a) {
		List<String> missing = new ArrayList<>();
		for (String need : new String[] { "diameter", "length", "dryMass" }) {
			if (!a.has(need)) {
				missing.add(need);
			}
		}
		if (!missing.isEmpty()) {
			return "also give the engine's " + String.join(", ", missing);
		}
		Rocket rocket = d.doc.getRocket();
		if (!a.has("mount")) {
			boolean any = false;
			for (RocketComponent c : rocket) {
				any |= c instanceof MotorMount mm && mm.isMotorMount();
			}
			if (!any) {
				return "make one of its tubes a motor mount";
			}
		}
		return rocket.getIds().isEmpty() ? "give it a flight configuration (set any motor once)" : null;
	}

	/** Flies the design with this burn as its motor (the design itself is untouched) and reads the altitude through it. */
	private static Flown fly(Context ctx, Designs.Design d, Args a, String type, EngineSim.Burn burn) throws IOException {
		Rocket rocket = d.doc.getRocket();
		RocketComponent comp = a.has("mount") ? Components.find(rocket, a.str("mount")) : firstMount(rocket);
		if (!(comp instanceof MotorMount)) {
			throw new ToolException("'" + comp.getName() + "' is not a motor mount.");
		}
		FlightConfiguration fc = rocket.getSelectedConfiguration();
		Args named = a.has("designation") && a.has("manufacturer") ? a : withNames(a);
		ThrustCurveMotor m = motor(ctx, named, type, burn, burn.curve(250), false);
		String mountId = comp.getID().toString();
		Simulation base = Sims.prepare(d, null, fc.getId().toString(), Sims.Overrides.none(), ctx.standards(), false, true);
		Simulation sim = Variants.ascentOnly(Variants.of(base, d.doc, r -> {
			MotorMount mm = (MotorMount) Components.find(r, mountId);
			mm.setMotorMount(true);
			MotorConfiguration mc = new MotorConfiguration(mm, fc.getId());
			mc.setMotor(m);
			mm.setMotorConfig(mc, fc.getId());
			r.getFlightConfiguration(fc.getId()).update();
		}, null));
		Variants.Run run = Variants.runAll(List.of(sim)).get(0);
		if (!run.ok()) {
			throw new ToolException("The design could not be flown on this engine: " + run.error());
		}
		Branch br = Branch.of(run.sim().getSimulatedData().getBranch(0));
		double[] t = br.col(FlightDataType.TYPE_TIME), alt = br.col(FlightDataType.TYPE_ALTITUDE);
		double site = run.sim().getOptions().getLaunchAltitude(), tb = burn.burnTime();
		DoubleUnaryOperator altitude = x -> {
			if (t.length == 0 || !(x > t[0])) {
				return 0;
			}
			for (int i = 1; i < t.length; i++) {
				if (x <= t[i]) {
					return alt[i - 1] + (alt[i] - alt[i - 1]) * (x - t[i - 1]) / Math.max(1e-9, t[i] - t[i - 1]);
				}
			}
			return alt[alt.length - 1];
		};
		return new Flown(x -> EngineSim.pressureAt(site + Math.max(0, altitude.applyAsDouble(x))), altitude.applyAsDouble(tb),
				run.sim().getSimulatedData().getMaxAltitude());
	}

	private static RocketComponent firstMount(Rocket rocket) {
		for (RocketComponent c : rocket) {
			if (c instanceof MotorMount mm && mm.isMotorMount()) {
				return c;
			}
		}
		throw new ToolException("The design has no motor mount: tick \"This is a motor mount\" on a tube, or name one with mount.");
	}

	/** The arguments with a stand-in name, for a motor that is only flown to find the altitude. */
	private static Args withNames(Args a) {
		JsonObject o = a.raw().deepCopy();
		if (!o.has("designation")) {
			o.addProperty("designation", "engine-under-study");
		}
		if (!o.has("manufacturer")) {
			o.addProperty("manufacturer", "study");
		}
		return new Args(o);
	}

	private static double finite(double v, double fallback) {
		return Double.isFinite(v) ? v : fallback;
	}

	private static String safe(String name) {
		return name.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static String capital(String s) {
		return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
	}
}
