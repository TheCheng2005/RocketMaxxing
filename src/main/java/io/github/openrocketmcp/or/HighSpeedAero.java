package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;

import info.openrocket.core.aerodynamics.AerodynamicForces;
import info.openrocket.core.aerodynamics.BarrowmanCalculator;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.SymmetricComponent;

/**
 * RocketMaxxing's high-speed drag model: OpenRocket's own drag with the two terms it leaves out or cannot describe,
 * the ones RASAero II is used for.
 *
 * <ul>
 * <li><b>Fin section.</b> OpenRocket knows square, rounded and "airfoil" fins, and gives an airfoil fin no wave drag
 * at all above Mach 1. Here a fin can be biconvex, double wedge or hexagonal (a flat plate with bevelled edges, the
 * usual high-power fin), and its supersonic thickness drag comes from linear (Ackeret) theory: cd = K (t/c)^2 /
 * sqrt(M^2 - 1) on the fin's planform area, K = 16/3 (biconvex), 4 (double wedge), 2 c/a (hexagonal with bevels of
 * length a). While the leading edge is swept behind the Mach cone the sweep sets it instead: K (t/c)^2 / tan(sweep)
 * (USAF DATCOM). It rises from Mach 0.85 to its Mach 1.2 value at Mach 1.05 (linear theory has no answer through
 * Mach 1). Sharp-edged sections have no leading-edge bluntness or trailing-edge base drag.</li>
 * <li><b>Power-on base drag.</b> While the motor burns its exhaust fills part of the base, so the base drag falls by
 * the share of the base the nozzle exits cover (Fleeman, Tactical Missile Design: CD_base,powered = (1 - A_e /
 * A_base) CD_base). OpenRocket uses the coasting value throughout.</li>
 * </ul>
 *
 * Everything else (friction with the finish and Reynolds number, nose, transition and shoulder pressure drag, rail
 * buttons, lugs, coasting base drag, and the centre of pressure) stays OpenRocket's. In a flight the model is applied
 * as a correction to OpenRocket's drag at that instant, so altitude and angle of attack still count.
 */
public final class HighSpeedAero {
	private HighSpeedAero() {
	}

	public static final String SOURCE = "RocketMaxxing high-speed model";
	public static final List<String> PROFILES = List.of("design", "hexagonal", "double_wedge", "biconvex");
	/** Share of the motor's diameter taken as its nozzle exit when none is given. */
	static final double EXIT_FRACTION = 0.6;
	/** A hexagonal fin's bevel when none is given, in fin thicknesses (about a 9.5 degree half-angle). */
	static final double BEVEL_THICKNESSES = 3;

	/**
	 * What the model was told: the nozzle exit diameter in m (NaN = 0.6 of each motor's diameter), the fin section
	 * ("design" = as OpenRocket has it, airfoil fins taken as biconvex), and a hexagonal fin's bevel length in m (NaN =
	 * three thicknesses).
	 */
	public record Settings(double nozzleExit, String finProfile, double bevel) {
		public Settings {
			finProfile = finProfile == null || finProfile.isBlank() ? "design" : finProfile.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
			if (!PROFILES.contains(finProfile)) {
				throw new IllegalArgumentException("Fin section \"" + finProfile + "\" is not one of " + String.join(", ", PROFILES) + ".");
			}
		}

		public static Settings defaults() {
			return new Settings(Double.NaN, "design", Double.NaN);
		}

		public JsonObject toJson() {
			JsonObject o = new JsonObject();
			if (!Double.isNaN(nozzleExit)) {
				o.addProperty("nozzleExitDiameter_m", nozzleExit);
			}
			o.addProperty("finProfile", finProfile);
			if (!Double.isNaN(bevel)) {
				o.addProperty("bevelLength_m", bevel);
			}
			return o;
		}

		public static Settings fromJson(JsonObject o) {
			return new Settings(o.has("nozzleExitDiameter_m") ? o.get("nozzleExitDiameter_m").getAsDouble() : Double.NaN,
					o.has("finProfile") ? o.get("finProfile").getAsString() : "design",
					o.has("bevelLength_m") ? o.get("bevelLength_m").getAsDouble() : Double.NaN);
		}
	}

	/** One Mach number: OpenRocket's CD, what the fins and the base contribute in each model, and the model's CD. */
	public record Row(double mach, double openRocket, double finOpenRocket, double finModel, double baseOff, double baseOn,
			double off, double on) {
	}

	/** What the model assumed for a configuration, in words, for the result. */
	public record Basis(List<String> fins, String base, double exitShare) {
	}

	// ------------------------------------------------------------------------------------------------ the physics

	/** Linear-theory thickness (wave) drag coefficient of a sharp-edged section on its planform area. */
	static double waveDrag(double mach, double k, double tc) {
		return waveDrag(mach, k, tc, 0);
	}

	/**
	 * As above for a fin whose leading edge is swept back by {@code sweep} (rad). While the leading edge is subsonic
	 * (sqrt(M^2 - 1) below tan sweep) the drag stays at K (t/c)^2 / tan sweep (USAF DATCOM 4.1.5.1): the sweep, not the
	 * Mach number, sets it.
	 */
	static double waveDrag(double mach, double k, double tc, double sweep) {
		double tan = Math.tan(Math.max(0, Math.min(sweep, Math.toRadians(80))));
		double at12 = k * tc * tc / Math.max(Math.sqrt(1.2 * 1.2 - 1), tan);
		if (mach >= 1.2) {
			return k * tc * tc / Math.max(Math.sqrt(mach * mach - 1), tan);
		}
		if (mach >= 1.05) {
			return at12;
		}
		if (mach <= 0.85) {
			return 0;
		}
		double x = (mach - 0.85) / 0.2;
		return at12 * x * x * (3 - 2 * x);
	}

	/** The sweep of a fin's leading edge: from the root's front to the first point at full span. */
	static double leadingEdgeSweep(FinSet fin) {
		var pts = fin.getFinPoints();
		double span = 0;
		for (var p : pts) {
			span = Math.max(span, p.y);
		}
		for (var p : pts) {
			if (span > 0 && p.y >= span - 1e-9) {
				return Math.atan2(Math.max(0, p.x - pts[0].x), span);
			}
		}
		return 0;
	}

	/** The thickness-drag factor K of a section; a = bevel length, c = chord (hexagonal only). */
	static double sectionFactor(String profile, double chord, double bevel) {
		return switch (profile) {
			case "biconvex" -> 16.0 / 3;
			case "double_wedge" -> 4;
			case "hexagonal" -> 2 * chord / Math.min(Math.max(bevel, 1e-6), chord / 2);
			default -> throw new IllegalArgumentException(profile);
		};
	}

	/** The section the model uses for a fin set, or null to keep OpenRocket's drag for it (square, rounded). */
	static String sectionOf(FinSet f, Settings s) {
		if (!s.finProfile().equals("design")) {
			return s.finProfile();
		}
		return f.getCrossSection() == FinSet.CrossSection.AIRFOIL ? "biconvex" : null;
	}

	public static List<Row> rows(FlightConfiguration fc, Settings s, double[] machs) {
		return rows(fc, s, machs, null);
	}

	/** The model over Mach numbers for the stack in {@code fc}; {@code basis} (if given) is filled with what it assumed. */
	public static List<Row> rows(FlightConfiguration fc, Settings s, double[] machs, Basis[] basis) {
		Analysis.settle(fc);
		List<Row> out = new ArrayList<>();
		BarrowmanCalculator calc = new BarrowmanCalculator();
		double exitShare = Double.NaN;
		for (double m : machs) {
			var c = Aero.conditions(fc, m);
			double sref = c.getRefArea();
			// One pass: the force analysis carries the whole rocket's total as its Rocket entry.
			Map<RocketComponent, AerodynamicForces> map = calc.getForceAnalysis(fc, c, new WarningSet());
			double total = Double.NaN;
			for (Map.Entry<RocketComponent, AerodynamicForces> e : map.entrySet()) {
				if (e.getKey() instanceof Rocket) {
					total = e.getValue().getCDaxial();
				}
			}
			if (Double.isNaN(total)) {
				total = calc.getAerodynamicForces(fc, c, new WarningSet()).getCDaxial();
			}
			double finOr = 0, finModel = 0, baseAll = 0, baseAft = 0, aftX = -Double.MAX_VALUE, aftArea = 0;
			List<String> finNotes = new ArrayList<>();
			String baseName = null;
			for (Map.Entry<RocketComponent, AerodynamicForces> e : map.entrySet()) {
				RocketComponent comp = e.getKey();
				AerodynamicForces f = e.getValue();
				int n = Aero.instances(fc, comp);
				if (comp instanceof FinSet fin) {
					String section = sectionOf(fin, s);
					double span = fin.getSpan(), area = fin.getPlanformArea();
					if (section == null || !(span > 0 && area > 0 && fin.getThickness() > 0)) {
						finNotes.add(fin.getName() + ": " + fin.getCrossSection().toString().toLowerCase(Locale.ROOT) + " edges, as OpenRocket has them");
						continue;
					}
					double chord = area / span, tc = fin.getThickness() / chord;
					double bevel = Double.isNaN(s.bevel()) ? BEVEL_THICKNESSES * fin.getThickness() : s.bevel();
					double k = sectionFactor(section, chord, bevel);
					finOr += n * (Aero.nz(f.getPressureCD()) + Aero.nz(f.getBaseCD()));
					double sweep = leadingEdgeSweep(fin);
					finModel += n * area / sref * waveDrag(m, k, tc, sweep);
					finNotes.add(fin.getName() + ": " + section.replace('_', ' ') + (section.equals("hexagonal")
							? String.format(Locale.ROOT, ", bevels %.1f mm", 1000 * Math.min(bevel, chord / 2)) : "")
							+ String.format(Locale.ROOT, ", t/c %.1f%%, leading edge swept %.0f deg", 100 * tc, Math.toDegrees(sweep)));
				} else if (comp instanceof SymmetricComponent sym) {
					double b = n * Aero.nz(f.getBaseCD());
					baseAll += b;
					double x = comp.getComponentLocations()[0].x + comp.getLength();
					if (b > 0 && x > aftX) {
						aftX = x;
						baseAft = b;
						aftArea = Math.PI * sym.getAftRadius() * sym.getAftRadius();
						baseName = comp.getName();
					}
				}
			}
			// The motors of the lowest stage flying exhaust through the aft base.
			double exits = 0;
			AxialStage bottom = fc.getBottomStage();
			for (MotorConfiguration mc : fc.getActiveMotors()) {
				if (mc.isEmpty() || mc.getMotor() == null || ((RocketComponent) mc.getMount()).getStage() != bottom) {
					continue;
				}
				double de = Double.isNaN(s.nozzleExit()) ? EXIT_FRACTION * mc.getMotor().getDiameter() : s.nozzleExit();
				exits += Aero.instances(fc, (RocketComponent) mc.getMount()) * Math.PI * de * de / 4;
			}
			exitShare = aftArea > 0 ? Math.min(1, exits / aftArea) : 0;
			double off = Math.max(0.02, total - finOr + finModel);
			double on = Math.max(0.02, off - baseAft * exitShare);
			out.add(new Row(m, total, finOr, finModel, baseAll, baseAll - baseAft * exitShare, off, on));
			if (basis != null) {
				basis[0] = new Basis(finNotes, baseName, exitShare);
			}
		}
		return out;
	}

	/** Normal force, axial drag and centre of pressure at an angle of attack (OpenRocket's model), for a Mach number. */
	public record Alpha(double mach, double alphaDeg, double cn, double cdAxial, double cpX) {
	}

	public static List<Alpha> alphas(FlightConfiguration fc, double[] machs, double[] alphasDeg) {
		Analysis.settle(fc);
		List<Alpha> out = new ArrayList<>();
		BarrowmanCalculator calc = new BarrowmanCalculator();
		for (double m : machs) {
			for (double a : alphasDeg) {
				var c = Aero.conditions(fc, m);
				c.setAOA(Math.toRadians(a));
				AerodynamicForces f = calc.getAerodynamicForces(fc, c, new WarningSet());
				var cp = f.getCP();
				out.add(new Alpha(m, a, f.getCN(), f.getCDaxial(), cp != null && cp.weight > 1e-9 ? cp.x : Double.NaN));
			}
		}
		return out;
	}

	static double[] grid() {
		List<Double> g = new ArrayList<>();
		for (double m = 0.1; m < 2.0 - 1e-9; m += 0.05) {
			g.add(Math.round(m * 100) / 100.0);
		}
		for (double m = 2.0; m <= 5.0 + 1e-9; m += 0.25) {
			g.add(Math.round(m * 100) / 100.0);
		}
		return g.stream().mapToDouble(Double::doubleValue).toArray();
	}

	/** The model as a table over Mach 0.1 to 5, with OpenRocket's CD beside it so a flight applies the difference. */
	public static AeroTable.Table table(FlightConfiguration fc, Settings s) {
		StringBuilder key0 = new StringBuilder();
		key0.append(fc.getRocket().getModID()).append('|').append(fc.getId()).append('|').append(AeroTable.stackOf(fc)).append('|')
				.append(fc.getModID()).append('|').append(s);
		// OpenRocket changes a mount's motor without a change event: the nozzle's share of the base follows the motor.
		for (MotorConfiguration mc : fc.getActiveMotors()) {
			if (!mc.isEmpty() && mc.getMotor() != null) {
				key0.append('|').append(((RocketComponent) mc.getMount()).getID()).append('=').append(mc.getMotor().getDesignation())
						.append('@').append(mc.getMotor().getDiameter());
			}
		}
		String key = key0.toString();
		synchronized (CACHE) {
			AeroTable.Table t = CACHE.get(key);
			if (t != null) {
				return t;
			}
		}
		double[] machs = grid();
		List<Row> rows = rows(fc, s, machs);
		int n = rows.size();
		double[] off = new double[n], on = new double[n], cp = new double[n], ref = new double[n];
		for (int i = 0; i < n; i++) {
			off[i] = rows.get(i).off();
			on[i] = rows.get(i).on();
			ref[i] = rows.get(i).openRocket();
			cp[i] = Double.NaN; // the centre of pressure stays OpenRocket's
		}
		AeroTable.Table t = new AeroTable.Table(machs, off, on, cp, SOURCE, true, ref, s);
		synchronized (CACHE) {
			CACHE.put(key, t);
		}
		return t;
	}

	private static final Map<String, AeroTable.Table> CACHE = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, AeroTable.Table> e) {
			return size() > 64;
		}
	});

	// ------------------------------------------------------------------------------------- switched on per design

	/** Designs flying on the model, by rocket id (copies keep the id), with what it was told. */
	private static final Map<String, Settings> ON = new ConcurrentHashMap<>();
	/** One simulation flown on the model (a preview) without the design being switched to it. */
	private static final Map<info.openrocket.core.document.Simulation, Settings> PREVIEW = Collections
			.synchronizedMap(new java.util.WeakHashMap<>());

	/** Flies {@code sim} on the model with {@code s}, whatever the design uses. */
	public static info.openrocket.core.document.Simulation preview(info.openrocket.core.document.Simulation sim, Settings s) {
		PREVIEW.put(sim, s);
		return sim;
	}

	/** The settings a flight of {@code sim} uses: its own preview's, else the design's, else null. */
	static Settings settingsFor(info.openrocket.core.document.Simulation sim) {
		Settings p = PREVIEW.remove(sim);
		return p != null ? p : settings(sim.getRocket());
	}

	public static void enable(Rocket r, Settings s) {
		ON.put(r.getID().toString(), s);
	}

	public static void disable(Rocket r) {
		ON.remove(r.getID().toString());
	}

	public static Settings settings(Rocket r) {
		return ON.get(r.getID().toString());
	}

	/**
	 * The tables a flight of {@code fc} uses when the design is on the model: one per stack that can fly (the whole
	 * vehicle and what is left after each separation), from the rocket as it is now. Empty when the model is off.
	 */
	static Map<AxialStage, AeroTable.Table> forFlight(FlightConfiguration fc, Settings s) {
		Map<AxialStage, AeroTable.Table> out = new LinkedHashMap<>();
		if (s == null) {
			return out;
		}
		List<AxialStage> stages = new ArrayList<>(fc.getRocket().getStageList());
		for (int k = 0; k < stages.size(); k++) {
			if (!fc.isStageActive(k)) {
				continue;
			}
			try {
				FlightConfiguration stack = Analysis.stack(fc, k);
				if (AeroTable.stackOf(stack) == stages.get(k)) {
					out.put(stages.get(k), table(stack, s));
				}
			} catch (RuntimeException e) {
				// a stack OpenRocket cannot analyse flies on its own model
			}
		}
		return out;
	}

	/** The whole vehicle's table for the configuration selected now, kept as the design's table for display. */
	public static AeroTable.Table refresh(Rocket r) {
		Settings s = settings(r);
		if (s == null) {
			return null;
		}
		AeroTable.Table t = table(r.getSelectedConfiguration(), s);
		if (AeroTable.of(r) != t) {
			AeroTable.put(r, t);
		}
		return t;
	}
}
