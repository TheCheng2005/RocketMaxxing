package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.RailButton;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationOptions;
import io.github.openrocketmcp.calc.Atmosphere;
import io.github.openrocketmcp.mcp.Issues;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Checks a simulated design against the active competition rule set and team standards.
 */
public final class Requirements {
	private Requirements() {
	}

	public enum Status {
		PASS, FAIL, WARN, INFO
	}

	public static final class Report {
		final List<Map<String, Object>> items = new ArrayList<>();
		List<String> manual;

		void add(Status s, String item, String requirement, String value, String ref) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("status", s.name());
			m.put("item", item);
			m.put("requirement", requirement);
			m.put("value", value);
			if (ref != null && !ref.isBlank()) {
				m.put("ref", ref);
			}
			items.add(m);
		}

		/**
		 * Rows that share a name (the fins of two stages, two boosters called the same) are numbered, so each can be
		 * told from the others and a failure is not read as the same one twice. Every row is kept: two boosters that
		 * both lack recovery are two failures.
		 */
		private void tidy() {
			Map<Object, Integer> named = new HashMap<>(), seen = new HashMap<>();
			for (Map<String, Object> i : items) {
				named.merge(i.get("item"), 1, Integer::sum);
			}
			for (Map<String, Object> i : items) {
				Object item = i.get("item");
				int of = named.get(item);
				if (of > 1) {
					i.put("item", item + " (" + seen.merge(item, 1, Integer::sum) + " of " + of + ")");
				}
			}
		}

		public Map<String, Object> render(String rules) {
			tidy();
			Map<String, Object> out = new LinkedHashMap<>();
			int fail = 0, warn = 0;
			for (Map<String, Object> i : items) {
				if ("FAIL".equals(i.get("status"))) {
					fail++;
				} else if ("WARN".equals(i.get("status"))) {
					warn++;
				}
			}
			out.put("ruleSet", rules);
			out.put("summary", fail == 0 ? (warn == 0 ? "All checks pass." : "No failures; " + warn + " warning(s).")
					: fail + " failure(s), " + warn + " warning(s).");
			out.put("checks", items);
			if (manual != null) {
				out.put("manualChecks", manual);
			}
			return out;
		}
	}

	/**
	 * Stability floor from the rule set: {floor, minimum calibers, % of body length, L:D}; floor = max(minimum calibers,
	 * % x L:D). Values are NaN (floor 0) when the rule set does not define them.
	 */
	public static double[] stabilityFloor(Standards std, FlightConfiguration fc) {
		double cal = std.rule(Analysis.hasDiameterChange(fc) ? "stability.minCalibersWithDiameterChange" : "stability.minCalibers",
				Dim.DIMENSIONLESS);
		double pct = std.rule("staticMarginPercentLength.min", Dim.DIMENSIONLESS);
		double ld = Dynamics.lengthToDiameter(fc);
		double floor = Math.max(Double.isNaN(cal) ? 0 : cal, Double.isNaN(pct) ? 0 : pct / 100 * ld);
		return new double[] { floor, cal, pct, ld };
	}

	/** A part named so it can be found: with its stage when the rocket has more than one. */
	static String where(RocketComponent c) {
		if (c == null) {
			return "?";
		}
		boolean staged = c.getRocket() != null && c.getRocket().getStageCount() > 1;
		return staged && c.getStage() != null && c.getStage() != c ? c.getName() + " in " + c.getStage().getName() : c.getName();
	}

	public static String floorText(double[] f) {
		return Units.num(f[0]) + " cal = max(" + Units.num(f[1]) + " cal, " + Units.num(f[2]) + "% of body length at L:D "
				+ Units.num(f[3]) + ")";
	}

	static double eventTime(FlightDataBranch b, FlightEvent.Type t) {
		return Sims.eventTime(b, t);
	}

	public static Report check(Simulation sim, Simulation windCase, Standards std) {
		Report r = new Report();
		if (std.rules().has("manualChecks")) {
			r.manual = new ArrayList<>();
			for (JsonElement e : std.rules().getAsJsonArray("manualChecks")) {
				r.manual.add(e.getAsString());
			}
		}
		FlightData data = sim.getSimulatedData();
		SimulationOptions opt = sim.getOptions();
		FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		boolean staged = fc.getActiveStageCount() > 1;
		FlightDataBranch main = data.getBranch(0);

		// A flight OpenRocket stopped, or one beyond what the models cover, puts every number below in doubt.
		// Physics (the flight itself) and output (numbers from beyond where a model is valid) in their own rows.
		for (String p : Sims.problems(sim)) {
			if (Issues.kindOf(p) == ToolException.Kind.OUTPUT) {
				r.add(Status.WARN, "Within the models' range", "the flight stays where OpenRocket's models are valid", p, "");
			} else {
				r.add(p.contains("stopped") ? Status.FAIL : Status.WARN, "Physically sound flight",
						"the simulation runs to landing", p, "");
			}
		}

		// Launch conditions used by the simulation
		double nominal = std.rule("launchAngleFromVertical.nominal", Dim.ANGLE);
		double tol = std.rule("launchAngleFromVertical.tolerance", Dim.ANGLE);
		if (!Double.isNaN(nominal)) {
			double angle = opt.getLaunchRodAngle();
			double stagedAngle = std.rule("launchAngleFromVertical.tiltInhibitedStaged", Dim.ANGLE);
			boolean ok = Math.abs(angle - nominal) <= tol + 1e-6
					|| (staged && !Double.isNaN(stagedAngle) && Math.abs(angle - stagedAngle) <= tol + 1e-6);
			r.add(ok ? Status.PASS : Status.WARN, "Simulated launch angle",
					Units.fmt(nominal, Dim.ANGLE) + " from vertical (84 deg elevation)" + (staged ? ", or 3 deg for tilt-inhibited staged flights" : ""),
					Units.fmt(angle, Dim.ANGLE), std.ruleRef("launchAngleFromVertical"));
		}
		double railRule = std.rule("railLength.value", Dim.LENGTH);
		if (!Double.isNaN(railRule) && Math.abs(opt.getLaunchRodLength() - railRule) > 0.05) {
			r.add(Status.INFO, "Simulated rail length", "LCRA rail is " + Units.fmt(railRule, Dim.LENGTH)
					+ " (use yours if you bring your own)", Units.fmt(opt.getLaunchRodLength(), Dim.LENGTH), std.ruleRef("railLength"));
		}
		if (Double.isNaN(std.q("launchSite.altitudeMsl", Dim.DISTANCE, Double.NaN))) {
			r.add(Status.WARN, "Launch site altitude",
					"Set the launch site's altitude in the team standards (launchSite.altitudeMsl) so air density is right",
					Units.fmt(opt.getLaunchAltitude(), Dim.DISTANCE) + " used by this simulation", null);
		}

		// Rail departure
		double railMin = std.rule("railDepartureVelocity.min", Dim.VELOCITY);
		double railAnalysis = std.rule("railDepartureVelocity.minWithDetailedAnalysis", Dim.VELOCITY);
		double railV = data.getLaunchRodVelocity();
		if (!Double.isNaN(railMin)) {
			Status s = railV >= railMin ? Status.PASS : !Double.isNaN(railAnalysis) && railV >= railAnalysis ? Status.WARN : Status.FAIL;
			r.add(s, "Rail departure velocity", ">= " + Units.fmt(railMin, Dim.VELOCITY)
					+ (Double.isNaN(railAnalysis) ? "" : " (>= " + Units.fmt(railAnalysis, Dim.VELOCITY) + " with detailed stability analysis)"),
					Units.fmt(railV, Dim.VELOCITY), std.ruleRef("railDepartureVelocity"));
		}

		// Thrust to weight at liftoff and per stage
		List<double[]> twr = ignitionTwr(sim, main);
		JsonElement byYear = std.rules().has("thrustToWeight") ? std.rules().getAsJsonObject("thrustToWeight").get("minByYear") : null;
		String year = std.str("competitionYear", "2026");
		double minTwr = byYear != null && byYear.getAsJsonObject().has(year) ? byYear.getAsJsonObject().get(year).getAsDouble() : Double.NaN;
		double recTwr = std.rule("thrustToWeight.recommended", Dim.DIMENSIONLESS);
		if (!twr.isEmpty()) {
			double liftoff = twr.get(0)[1];
			if (!Double.isNaN(minTwr)) {
				Status s = liftoff < minTwr ? Status.FAIL : !Double.isNaN(recTwr) && liftoff < recTwr ? Status.WARN : Status.PASS;
				r.add(s, "Thrust-to-weight at liftoff (avg thrust / liftoff weight)",
						">= " + Units.num(minTwr) + " in " + year + (Double.isNaN(recTwr) ? "" : "; design for >= " + Units.num(recTwr)),
						Units.num(liftoff), std.ruleRef("thrustToWeight"));
			}
			if (staged) {
				double boosterMin = std.rule("thrustToWeight.stagedBoosterMin", Dim.DIMENSIONLESS);
				double sustainerMin = std.rule("thrustToWeight.stagedSustainerMin", Dim.DIMENSIONLESS);
				if (!Double.isNaN(boosterMin)) {
					r.add(liftoff >= boosterMin ? Status.PASS : Status.FAIL, "Booster thrust-to-weight (staged)",
							">= " + Units.num(boosterMin), Units.num(liftoff), std.ruleRef("thrustToWeight"));
				}
				for (int i = 1; i < twr.size(); i++) {
					if (!Double.isNaN(sustainerMin)) {
						r.add(twr.get(i)[1] >= sustainerMin ? Status.PASS : Status.FAIL,
								"Upper-stage thrust-to-weight at ignition (t=" + Units.num(twr.get(i)[0]) + " s)",
								">= " + Units.num(sustainerMin), Units.num(twr.get(i)[1]), std.ruleRef("thrustToWeight"));
					}
				}
			}
		}

		// Stability
		stability(r, sim, std, "Ascent stability", fc);
		if (windCase != null) {
			stability(r, windCase, std, "Ascent stability in " + Units.fmt(Winds.speed(windCase.getOptions()), Dim.VELOCITY) + " wind", fc);
			double railW = windCase.getSimulatedData().getLaunchRodVelocity();
			r.add(Status.INFO, "Rail departure velocity in design wind", "same as above", Units.fmt(railW, Dim.VELOCITY), std.ruleRef("maxGroundWind"));
		}

		// Staging
		if (staged) {
			double frac = std.rule("staging.ignitionAltitudeFraction", Dim.DIMENSIONLESS);
			double tiltMax = std.rule("staging.tiltLimitMax", Dim.ANGLE);
			double tiltRec = std.rule("staging.tiltLimitRecommended", Dim.ANGLE);
			for (FlightEvent e : main.getEvents()) {
				if (e.getType() == FlightEvent.Type.IGNITION && e.getTime() > 0.01) {
					double alt = Sims.at(main, FlightDataType.TYPE_ALTITUDE, e.getTime());
					double tilt = Sims.tilt(main, e.getTime());
					if (!Double.isNaN(tiltMax)) {
						Status s = tilt > tiltMax ? Status.FAIL : !Double.isNaN(tiltRec) && tilt > tiltRec ? Status.WARN : Status.PASS;
						r.add(s, "Tilt at upper-stage ignition (" + where(e.getSource()) + ")",
								"tilt lockout <= " + Units.fmt(tiltMax, Dim.ANGLE) + ", should be < " + Units.fmt(tiltRec, Dim.ANGLE)
										+ "; nominal flight must be well inside the lockout",
								Units.fmt(tilt, Dim.ANGLE), std.ruleRef("staging"));
					}
					if (!Double.isNaN(frac)) {
						r.add(Status.INFO, "Air-start altitude inhibit (" + where(e.getSource()) + ")",
								"program inhibit at " + Units.num(frac * 100) + "% of the simulated ignition altitude",
								"simulated " + Units.fmt(alt, Dim.DISTANCE) + " -> inhibit below " + Units.fmt(frac * alt, Dim.DISTANCE),
								std.ruleRef("staging"));
					}
				}
			}
		}

		// Supersonic flight: OpenRocket's aerodynamics are less accurate transonic/supersonic and fin flutter is not modeled.
		double mach = data.getMaxMachNumber();
		if (mach > 0.9) {
			r.add(mach > 1 ? Status.WARN : Status.INFO, "Maximum Mach number",
					"transonic/supersonic: verify with RASAero; check fin/nose materials and heating (flutter is checked below)",
					Units.num(mach), null);
		}

		importedCp(r, sim, std, fc);
		flutter(r, sim, std);
		edicts(r, sim, fc, std);

		// Recovery, per branch
		earlyDeployments(r, sim);
		recovery(r, sim, std);
		return r;
	}

	private static void stability(Report r, Simulation sim, Standards std, String label, FlightConfiguration fc) {
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		double rail = eventTime(b, FlightEvent.Type.LAUNCHROD);
		double apogee = eventTime(b, FlightEvent.Type.APOGEE);
		if (Double.isNaN(rail) || Double.isNaN(apogee)) {
			r.add(Status.WARN, label, "stable for the entire ascent", "no rail exit/apogee in simulation", std.ruleRef("stability"));
			return;
		}
		boolean diameterChange = Analysis.hasDiameterChange(fc);
		double min = std.rule(diameterChange ? "stability.minCalibersWithDiameterChange" : "stability.minCalibers", Dim.DIMENSIONLESS);
		if (Double.isNaN(min)) {
			return;
		}
		// Ignore the last seconds before apogee, where airspeed is too low for a meaningful static margin.
		Sims.Window w = Sims.extremes(b, FlightDataType.TYPE_STABILITY, rail, apogee, 30.48);
		double aoa = Sims.at(b, FlightDataType.TYPE_AOA, w.minTime());
		String why = aoa > Math.toRadians(3)
				? "; angle of attack " + Units.fmt(aoa, Dim.ANGLE) + " there (wind / rail exit): OpenRocket's CP moves forward at high angle of attack. Zero-AoA margin: "
						+ Units.num(zeroAoaMargin(sim, b, w.minTime())) + " cal"
				: "";
		r.add(w.min() >= min ? Status.PASS : Status.FAIL, label + " (minimum, rail exit to apogee while airspeed > 100 ft/s)",
				">= " + Units.num(min) + " cal" + (diameterChange ? " (diameter change: use RASAero CP/CD overrides)" : ""),
				Units.num(w.min()) + " cal at t=" + Units.num(w.minTime()) + " s, Mach "
						+ Units.num(Sims.at(b, FlightDataType.TYPE_MACH_NUMBER, w.minTime())) + why,
				std.ruleRef("stability"));
		double concern = std.rule("stability.overStableConcern", Dim.DIMENSIONLESS);
		double over = std.rule("stability.overStable", Dim.DIMENSIONLESS);
		if (!Double.isNaN(over)) {
			Status s = w.max() >= over ? Status.WARN : !Double.isNaN(concern) && w.max() > concern ? Status.WARN : Status.PASS;
			r.add(s, label + " (maximum, over-stability)", "should stay below ~" + Units.num(concern) + " cal; >= "
					+ Units.num(over) + " cal is over-stable", Units.num(w.max()) + " cal", std.ruleRef("stability"));
		}
	}

	/** Checks from the Launch Canada 2027 edicts (present only in rule sets that define them). */
	static void edicts(Report r, Simulation sim, FlightConfiguration fc, Standards std) {
		double ldMax = std.rule("lengthToDiameter.max", Dim.DIMENSIONLESS);
		if (!Double.isNaN(ldMax)) {
			double ld = Dynamics.lengthToDiameter(fc);
			double ldRec = std.rule("lengthToDiameter.recommendedMax", Dim.DIMENSIONLESS);
			r.add(ld > ldMax ? Status.FAIL : ld > ldRec ? Status.WARN : Status.PASS, "Length-to-diameter ratio",
					"shall not exceed " + Units.num(ldMax) + ", should not exceed " + Units.num(ldRec), Units.num(ld),
					std.ruleRef("lengthToDiameter"));
		}
		double zMin = std.rule("dampingRatio.min", Dim.DIMENSIONLESS);
		double pctMin = std.rule("staticMarginPercentLength.min", Dim.DIMENSIONLESS);
		if (!Double.isNaN(zMin) || !Double.isNaN(pctMin)) {
			List<Dynamics.Sample> samples = Dynamics.ascent(sim, 30.48, 150);
			if (!Double.isNaN(zMin) && !samples.isEmpty()) {
				double zMax = std.rule("dampingRatio.max", Dim.DIMENSIONLESS);
				double zrMin = std.rule("dampingRatio.recommendedMin", Dim.DIMENSIONLESS);
				double zrMax = std.rule("dampingRatio.recommendedMax", Dim.DIMENSIONLESS);
				Dynamics.Sample lo = null, hi = null;
				for (Dynamics.Sample x : samples) {
					if (Double.isNaN(x.dampingRatio())) {
						continue;
					}
					lo = lo == null || x.dampingRatio() < lo.dampingRatio() ? x : lo;
					hi = hi == null || x.dampingRatio() > hi.dampingRatio() ? x : hi;
				}
				if (lo != null) {
					Status st = lo.dampingRatio() < zMin || hi.dampingRatio() > zMax ? Status.FAIL
							: lo.dampingRatio() < zrMin || hi.dampingRatio() > zrMax ? Status.WARN : Status.PASS;
					r.add(st, "Damping ratio during ascent (airspeed > 100 ft/s)",
							Units.num(zMin) + " to " + Units.num(zMax) + " (shall), " + Units.num(zrMin) + " to " + Units.num(zrMax)
									+ " (should); low = weak damping of oscillations, high = hard weathercocking",
							Units.num(lo.dampingRatio()) + " (t=" + Units.num(lo.time()) + " s, Mach " + Units.num(lo.mach()) + ") to "
									+ Units.num(hi.dampingRatio()) + " (t=" + Units.num(hi.time()) + " s)",
							std.ruleRef("dampingRatio"));
				}
			}
			if (!Double.isNaN(pctMin)) {
				double end = Dynamics.marginWindowEnd(sim);
				Dynamics.Sample worst = null;
				for (Dynamics.Sample x : samples) {
					if ((Double.isNaN(end) || x.time() <= end) && !Double.isNaN(x.marginPercentLength())
							&& (worst == null || x.marginPercentLength() < worst.marginPercentLength())) {
						worst = x;
					}
				}
				if (worst != null) {
					r.add(worst.marginPercentLength() >= pctMin ? Status.PASS : Status.FAIL,
							"Static margin as % of body length (rail exit to 2 x burn time" + (Double.isNaN(end) ? "" : ", t <= "
									+ Units.num(end) + " s") + ")",
							">= " + Units.num(pctMin) + "% of body length (= " + Units.num(pctMin / 100 * Dynamics.lengthToDiameter(fc))
									+ " cal for this L:D)",
							Units.num(worst.marginPercentLength()) + "% (" + Units.num(worst.marginCalibers()) + " cal) at t="
									+ Units.num(worst.time()) + " s",
							std.ruleRef("staticMarginPercentLength"));
				}
			}
		}
		if (std.rules().has("railButtons")) {
			for (RocketComponent c : fc.getRocket()) {
				if (c instanceof RailButton rb && fc.isComponentActive(c)) {
					String mat = rb.getMaterial().getName().toLowerCase(Locale.ROOT);
					boolean metal = mat.contains("aluminum") || mat.contains("aluminium") || mat.contains("steel")
							|| mat.contains("brass") || mat.contains("titanium") || mat.contains("copper");
					r.add(metal ? Status.FAIL : Status.PASS, "Rail button material (" + where(c) + ")",
							"no metal rail buttons; machine them from Delrin (POM)", rb.getMaterial().getName(), std.ruleRef("railButtons"));
				}
			}
		}
		double machAv = std.rule("avionicsMachConcentric.value", Dim.DIMENSIONLESS);
		if (!Double.isNaN(machAv) && sim.getSimulatedData().getMaxMachNumber() > machAv) {
			r.add(Status.INFO, "Avionics bay layout (Mach " + Units.num(sim.getSimulatedData().getMaxMachNumber()) + ")",
					"above Mach " + Units.num(machAv) + ": concentric altimeter bay with concentric air sampling holes; crescent "
							+ "bays only in RF-transparent material", "verify in CAD", std.ruleRef("avionicsMachConcentric"));
		}
		electronics(r, fc);
		double ispMin = std.rule("srad.minStaticFireIsp", Dim.TIME);
		if (!Double.isNaN(ispMin)) {
			for (MotorConfiguration mc : fc.getActiveMotors()) {
				if (mc.getMotor() instanceof ThrustCurveMotor m
						&& (m.getMotorType() == Motor.Type.HYBRID
								|| m.getMotorType() == Motor.Type.UNKNOWN)) {
					double prop = m.getLaunchMass() - m.getBurnoutMass();
					double isp = prop > 0 ? m.getTotalImpulseEstimate() / (prop * Atmosphere.G0) : Double.NaN;
					r.add(isp >= ispMin ? Status.PASS : Status.FAIL, "SRAD/hybrid/liquid engine Isp (" + m.getDesignation() + ")",
							">= " + Units.num(ispMin) + " s in static fires to come to competition (higher by probation level; "
									+ "see advanced_probation)",
							Units.num(isp) + " s (total impulse / propellant weight)", std.ruleRef("srad"));
				}
			}
		}
		if (std.rules().has("popTests")) {
			List<String> tests = new ArrayList<>();
			for (RocketComponent c : fc.getRocket()) {
				if (c instanceof RecoveryDevice && fc.isComponentActive(c)) {
					tests.add("pop test: " + c.getName() + " bay");
				}
			}
			for (AxialStage st : fc.getActiveStages()) {
				if (st.getStageNumber() > 0) {
					tests.add("separation test: " + st.getName());
				}
			}
			if (!tests.isEmpty()) {
				r.add(Status.INFO, "Assembly dress rehearsal tests", "pop-test every component that separates in flight, with the "
						+ "finished rocket (no change to nose weight, tolerances or shear pins); yank test for single-bay dual deploy",
						String.join("; ", tests), std.ruleRef("popTests"));
			}
		}
	}

	/** A recovery device opening before apogee (e.g. OpenRocket's default "motor ejection charge" with a short delay). */
	/**
	 * Stability with each imported (e.g. RASAero) CP table and the simulated CG: the whole vehicle until its first
	 * separation, and each stack with its own table while it flies alone (the sustainer after the booster drops).
	 */
	static void importedCp(Report r, Simulation sim, Standards std, FlightConfiguration fc) {
		List<AxialStage> stages = new ArrayList<>(sim.getRocket().getStageList());
		for (Map.Entry<AxialStage, AeroTable.Table> e : AeroTable.all(sim.getRocket()).entrySet()) {
			AeroTable.Table t = e.getValue();
			int k = stages.indexOf(e.getKey());
			boolean whole = k == stages.size() - 1; // the whole vehicle's table, whichever stages this configuration flies
			if (!t.hasCp() || k < 0 || !whole && !fc.isStageActive(k)) {
				continue;
			}
			FlightConfiguration stack = whole ? fc : Analysis.stack(fc, k);
			AeroTable.Margin m = AeroTable.minMargin(sim, t, Analysis.maxDiameter(stack), e.getKey());
			if (m == null) {
				continue;
			}
			double min = std.rule(Analysis.hasDiameterChange(stack) ? "stability.minCalibersWithDiameterChange" : "stability.minCalibers",
					Dim.DIMENSIONLESS);
			String what = whole ? "until apogee or staging" : "while " + Analysis.stackName(fc, k) + " flies alone";
			r.add(Double.isNaN(min) ? Status.INFO : m.min() >= min ? Status.PASS : Status.FAIL,
					"Ascent stability with imported CP (" + t.source() + ")" + (whole ? "" : ", " + Analysis.stackName(fc, k)),
					Double.isNaN(min) ? "for information" : ">= " + Units.num(min) + " cal (imported CP vs simulated CG, " + what + ")",
					Units.num(m.min()) + " cal at t=" + Units.num(m.time()) + " s (Mach " + Units.num(m.mach()) + ")", std.ruleRef("stability"));
		}
	}

	/** Fin flutter margin along the flight (team standard, not a competition rule). */
	static void flutter(Report r, Simulation sim, Standards std) {
		double need = std.q("structures.flutterMinMargin", Dim.DIMENSIONLESS, 1.5);
		List<Structures.FlutterResult> res;
		try {
			res = Structures.flutter(sim, std, null, Double.NaN);
		} catch (ToolException e) {
			r.add(Status.INFO, "Fin flutter", "flutter speed >= " + Units.num(need) + " x airspeed (team standard)",
					"not evaluated: " + e.getMessage(), null);
			return;
		}
		for (Structures.FlutterResult f : res) {
			if (Double.isNaN(f.minMargin())) {
				continue;
			}
			Status s = f.minMargin() < 1 ? Status.FAIL : f.minMargin() < need ? Status.WARN : Status.PASS;
			r.add(s, "Fin flutter margin (" + where(f.fin()) + ", " + f.fin().getMaterial().getName() + ")",
					"flutter speed >= " + Units.num(need) + " x airspeed along the flight (team standard; NACA TN 4197 screen, "
							+ "see fin_flutter)",
					Units.num(f.minMargin()) + " at " + Units.fmt(f.altitude(), Dim.DISTANCE) + ", " + Units.fmt(f.airspeed(), Dim.VELOCITY),
					"structures.flutterMinMargin");
		}
	}

	static void earlyDeployments(Report r, Simulation sim) {
		for (String late : lateFirstDeployments(sim)) {
			r.add(Status.FAIL, late, "the initial deployment event shall occur at or near apogee", "", "R4.2.1");
		}
		for (Sims.Deployment d : Sims.deployments(sim)) {
			if (!Double.isNaN(d.timeAfterApogee()) && d.timeAfterApogee() < -0.5) {
				r.add(Status.FAIL, d.branch() + ": " + d.device().getName() + " deploys before apogee",
						"recovery devices must not open during ascent (check the deployment event: new parachutes in "
								+ "OpenRocket default to the motor ejection charge; use set_deployment)",
						Units.num(-d.timeAfterApogee()) + " s before apogee at " + Units.fmt(d.airspeed(), Dim.VELOCITY),
						"R4.2.1");
			}
		}
	}

	/** First deployment of each branch more than 3 s after apogee: the vehicle is falling fast when it opens. */
	/**
	 * Electronics modelled as typed OpenRocket parts (altimeter, flight computer, tracker, battery): what the design
	 * carries, and whether every deployment altimeter and tracker can have its own battery (Launch Canada: individual
	 * power supply for each deployment altimeter and each GPS / RF beacon).
	 */
	static void electronics(Report r, FlightConfiguration fc) {
		int alt = 0, trk = 0, bat = 0;
		for (RocketComponent c : fc.getAllActiveComponents()) {
			if (c instanceof MassComponent m) {
				switch (m.getMassComponentType()) {
					case ALTIMETER, FLIGHTCOMPUTER -> alt++;
					case TRACKER -> trk++;
					case BATTERY -> bat++;
					default -> {
					}
				}
			}
		}
		if (alt + trk == 0) {
			r.add(Status.INFO, "Electronics in the design", "model altimeters, trackers and batteries as typed mass components "
					+ "(add_avionics_bay does) so mass, CG and the checks include them", "none modelled", null);
			return;
		}
		String have = alt + " altimeter(s) / flight computer(s), " + trk + " tracker(s), " + bat + " battery(ies)";
		r.add(alt >= 2 ? Status.PASS : Status.WARN, "Redundant deployment electronics",
				"two independent altimeters (each on its own circuit)", have, "2027 Edicts, Electronics");
		r.add(bat >= alt + trk ? Status.PASS : Status.WARN, "Individual power supply per altimeter and tracker",
				"one battery for each deployment altimeter and each GPS / RF beacon", bat + " batteries for " + (alt + trk)
						+ " devices", "2027 Edicts, Electronics");
	}

	public static List<String> lateFirstDeployments(Simulation sim) {
		List<String> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Sims.Deployment d : Sims.deployments(sim)) {
			if (!seen.add(d.branch())) {
				continue;
			}
			if (!Double.isNaN(d.timeAfterApogee()) && d.timeAfterApogee() > 3) {
				out.add(d.branch() + ": first deployment (" + d.device().getName() + ") " + Units.num(d.timeAfterApogee())
						+ " s after apogee at " + Units.fmt(d.airspeed(), Dim.VELOCITY));
			}
		}
		return out;
	}

	private static void recovery(Report r, Simulation sim, Standards std) {
		double dualAbove = std.rule("dualEventAboveApogeeAgl.value", Dim.DISTANCE);
		double drMin = std.rule("drogueDescentRate.min", Dim.VELOCITY);
		double drMax = std.rule("drogueDescentRate.max", Dim.VELOCITY);
		double drRecMin = std.rule("drogueDescentRate.recommendedMin", Dim.VELOCITY);
		double drRecMax = std.rule("drogueDescentRate.recommendedMax", Dim.VELOCITY);
		double mainAltMax = std.rule("mainDeployAltitudeMaxAgl.value", Dim.DISTANCE);
		double mainMax = std.rule("mainDescentRate.max", Dim.VELOCITY);
		double mainRec = std.rule("mainDescentRate.recommended", Dim.VELOCITY);
		List<Sims.Deployment> deps = Sims.deployments(sim);
		for (FlightDataBranch b : sim.getSimulatedData().getBranches()) {
			List<Sims.Deployment> mine = new ArrayList<>();
			for (Sims.Deployment d : deps) {
				if (d.branch().equals(b.getName())) {
					mine.add(d);
				}
			}
			double apogee = b.getMaximum(FlightDataType.TYPE_ALTITUDE);
			String name = b.getName();
			if (mine.isEmpty()) {
				r.add(Status.FAIL, name + ": recovery", "every independently recovered body needs recovery", "no deployment in simulation", null);
				continue;
			}
			if (!Double.isNaN(dualAbove) && apogee > dualAbove) {
				r.add(mine.size() >= 2 ? Status.PASS : Status.FAIL, name + ": dual-event recovery",
						"required above " + Units.fmt(dualAbove, Dim.DISTANCE) + " AGL apogee",
						mine.size() + " deployment event(s), apogee " + Units.fmt(apogee, Dim.DISTANCE), std.ruleRef("dualEventAboveApogeeAgl"));
			}
			for (int i = 0; i < mine.size(); i++) {
				Sims.Deployment d = mine.get(i);
				String role = Recovery.role(mine, i);
				double v = d.steadyDescentRate();
				if (role.equals("drogue") && !Double.isNaN(drMin)) {
					Status s = v < drMin || v > drMax ? Status.FAIL
							: !Double.isNaN(drRecMin) && (v < drRecMin || v > drRecMax) ? Status.WARN : Status.PASS;
					r.add(s, name + ": drogue descent rate (" + d.device().getName() + ")",
							Units.fmt(drMin, Dim.VELOCITY) + " to " + Units.fmt(drMax, Dim.VELOCITY) + "; should be "
									+ Units.fmt(drRecMin, Dim.VELOCITY) + " to " + Units.fmt(drRecMax, Dim.VELOCITY),
							Units.fmt(v, Dim.VELOCITY), std.ruleRef("drogueDescentRate"));
				}
				if (role.startsWith("main")) {
					if (!Double.isNaN(mainAltMax) && mine.size() > 1) {
						r.add(d.altitudeAgl() <= mainAltMax + 1 ? Status.PASS : Status.FAIL,
								name + ": main deployment altitude (" + d.device().getName() + ")",
								"<= " + Units.fmt(mainAltMax, Dim.DISTANCE) + " AGL", Units.fmt(d.altitudeAgl(), Dim.DISTANCE),
								std.ruleRef("mainDeployAltitudeMaxAgl"));
					}
					if (!Double.isNaN(mainMax)) {
						Status s = v >= mainMax ? Status.FAIL : !Double.isNaN(mainRec) && v > mainRec * 1.25 ? Status.WARN : Status.PASS;
						r.add(s, name + ": landing descent rate (" + d.device().getName() + ")",
								"< " + Units.fmt(mainMax, Dim.VELOCITY) + ", ideally about " + Units.fmt(mainRec, Dim.VELOCITY),
								Units.fmt(v, Dim.VELOCITY), std.ruleRef("mainDescentRate"));
					}
				}
			}
		}
	}

	/** Static margin at zero angle of attack at time t: (Barrowman CP at that Mach - simulated CG) / reference diameter. */
	static double zeroAoaMargin(Simulation sim, FlightDataBranch b, double t) {
		FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		double cp = Analysis.cp(fc, Sims.at(b, FlightDataType.TYPE_MACH_NUMBER, t));
		double cg = Sims.at(b, FlightDataType.TYPE_CG_LOCATION, t);
		return (cp - cg) / Analysis.maxDiameter(fc);
	}

	/** [time, average thrust-to-weight] for each ignition in the main branch. */
	static List<double[]> ignitionTwr(Simulation sim, FlightDataBranch b) {
		List<double[]> out = new ArrayList<>();
		for (FlightEvent e : b.getEvents()) {
			if (e.getType() != FlightEvent.Type.IGNITION || !(e.getSource() instanceof MotorMount mount)) {
				continue;
			}
			MotorConfiguration mc = mount.getMotorConfig(sim.getFlightConfigurationId());
			if (mc == null || !(mc.getMotor() instanceof ThrustCurveMotor m)) {
				continue;
			}
			double mass = Sims.at(b, FlightDataType.TYPE_MASS, e.getTime());
			double thrust = m.getAverageThrustEstimate() * mc.getMotorCount();
			// Several motors igniting together (clusters, parallel boosters) add up.
			if (!out.isEmpty() && Math.abs(out.get(out.size() - 1)[0] - e.getTime()) < 0.01) {
				double[] last = out.get(out.size() - 1);
				last[1] += thrust / (mass * Atmosphere.G0);
			} else {
				out.add(new double[] { e.getTime(), thrust / (mass * Atmosphere.G0) });
			}
		}
		return out;
	}
}
