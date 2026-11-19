package io.github.openrocketmcp.or;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.logging.Warning;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.simulation.listeners.SimulationListener;
import info.openrocket.core.unit.UnitGroup;
import io.github.openrocketmcp.calc.Atmosphere;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.UnitSystem;
import io.github.openrocketmcp.units.Units;

/**
 * Running simulations and extracting the numbers competition teams need from them.
 */
public final class Sims {
	private Sims() {
	}

	/** Launch/atmosphere overrides for one run. NaN / null means "keep the simulation's value". */
	public record Overrides(double windSpeed, double windDirection, double windTurbulence, double rodLength,
			double rodAngle, double rodDirection, double launchAltitude, double latitude, double longitude,
			double temperature, Boolean launchIntoWind, double maxTime) {
		public static Overrides none() {
			return new Overrides(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
					Double.NaN, Double.NaN, Double.NaN, null, Double.NaN);
		}

		boolean any() {
			return !Double.isNaN(windSpeed) || !Double.isNaN(windDirection) || !Double.isNaN(windTurbulence)
					|| !Double.isNaN(rodLength) || !Double.isNaN(rodAngle) || !Double.isNaN(rodDirection)
					|| !Double.isNaN(launchAltitude) || !Double.isNaN(latitude) || !Double.isNaN(longitude)
					|| !Double.isNaN(temperature) || launchIntoWind != null || !Double.isNaN(maxTime);
		}
	}

	static final int NEW_SIMULATION_SEED = 20270801;

	public static Simulation find(Designs.Design d, String ref) {
		List<Simulation> sims = d.doc.getSimulations();
		if (ref == null || ref.isBlank()) {
			return null;
		}
		for (Simulation s : sims) {
			if (s.getName().equalsIgnoreCase(ref.trim())) {
				return s;
			}
		}
		try {
			int i = Integer.parseInt(ref.trim());
			if (i >= 0 && i < sims.size()) {
				return sims.get(i);
			}
		} catch (NumberFormatException ignored) {
			// not an index
		}
		List<String> names = new ArrayList<>();
		for (int i = 0; i < sims.size(); i++) {
			names.add(i + ": " + sims.get(i).getName());
		}
		throw new ToolException("No simulation '" + ref + "'. Simulations: " + names);
	}

	/**
	 * Picks the simulation to run: the named one, else the first simulation of the requested (or selected)
	 * flight configuration, else a new one created with the team's launch-site defaults and added to the
	 * document. With overrides, a copy is returned so the document's simulation is not changed.
	 */
	public static Simulation prepare(Designs.Design d, String simRef, String configRef, Overrides o, Standards std) {
		return prepare(d, simRef, configRef, o, std, true);
	}

	/** As above; with {@code persistNew == false} a newly created simulation is not added to the document. */
	public static Simulation prepare(Designs.Design d, String simRef, String configRef, Overrides o, Standards std,
			boolean persistNew) {
		return prepare(d, simRef, configRef, o, std, persistNew, false);
	}

	/** As above; {@code allowNoMotors} lets callers that set motors per variant (motor ranking) start from an empty configuration. */
	public static Simulation prepare(Designs.Design d, String simRef, String configRef, Overrides o, Standards std,
			boolean persistNew, boolean allowNoMotors) {
		Rocket rocket = d.doc.getRocket();
		Simulation base = find(d, simRef);
		if (base == null) {
			FlightConfiguration fc = Components.config(rocket, configRef);
			for (Simulation s : d.doc.getSimulations()) {
				if (fc.getId().equals(s.getFlightConfigurationId())) {
					base = s;
					break;
				}
			}
			if (base == null) {
				if (!fc.hasMotors() && !allowNoMotors) {
					throw new ToolException("Flight configuration '" + fc.getName() + "' has no motors. Use set_motor first.");
				}
				base = new Simulation(d.doc, rocket);
				base.setFlightConfigurationId(fc.getId());
				base.setName("Simulation of " + fc.getName()); // named like one the team made, with what it flies
				std.applyLaunchDefaults(base.getOptions());
				// OpenRocket seeds new simulations randomly; a fixed seed makes repeated tool calls agree.
				base.getOptions().setRandomSeed(NEW_SIMULATION_SEED);
				if (!persistNew) {
					return o == null ? base : applied(base, o);
				}
				d.doc.addSimulation(base);
			}
		}
		// A simulation of a configuration with no motor never leaves the pad: every result read from it would be empty
		// (a landing speed of n/a, no fin loads), so say what is missing instead.
		FlightConfiguration flown = rocket.getFlightConfiguration(base.getFlightConfigurationId());
		if (!allowNoMotors && flown != null && !flown.hasMotors()) {
			throw new ToolException("Flight configuration '" + flown.getName() + "' (simulation '" + base.getName()
					+ "') has no motor, so there is no flight to take results from. Choose a motor first (set_motor).");
		}
		if (o == null || !o.any()) {
			return base;
		}
		Simulation copy = base.copy();
		apply(copy.getOptions(), o);
		return copy;
	}

	private static Simulation applied(Simulation sim, Overrides o) {
		apply(sim.getOptions(), o);
		return sim;
	}

	static void apply(SimulationOptions opt, Overrides o) {
		// With a multi-level profile, the override scales / rotates the profile from its lowest level.
		Winds.setGround(opt, o.windSpeed(), o.windDirection());
		if (!Double.isNaN(o.windTurbulence())) {
			Winds.setTurbulence(opt, o.windTurbulence());
		}
		if (!Double.isNaN(o.rodLength())) {
			opt.setLaunchRodLength(o.rodLength());
		}
		if (!Double.isNaN(o.rodAngle())) {
			opt.setLaunchRodAngle(o.rodAngle());
		}
		if (!Double.isNaN(o.rodDirection())) {
			opt.setLaunchRodDirection(o.rodDirection());
		}
		if (!Double.isNaN(o.launchAltitude())) {
			opt.setLaunchAltitude(o.launchAltitude());
		}
		if (!Double.isNaN(o.latitude())) {
			opt.setLaunchLatitude(o.latitude());
		}
		if (!Double.isNaN(o.longitude())) {
			opt.setLaunchLongitude(o.longitude());
		}
		if (!Double.isNaN(o.temperature())) {
			opt.setISAAtmosphere(false);
			opt.setLaunchTemperature(o.temperature());
			opt.setLaunchPressure(Atmosphere.at(opt.getLaunchAltitude()).pressure());
		}
		if (o.launchIntoWind() != null) {
			opt.setLaunchIntoWind(o.launchIntoWind());
		}
		if (!Double.isNaN(o.maxTime())) {
			opt.setMaxSimulationTime(o.maxTime());
		}
	}

	/** What each simulation was last flown with, beyond what OpenRocket tracks itself (see {@link #ensure}). */
	private static final Map<Simulation, String> FLOWN = Collections.synchronizedMap(new WeakHashMap<>());

	/** The parts of a run OpenRocket's own staleness check does not cover. */
	public static String stamp(Simulation sim) {
		SimulationOptions o = sim.getOptions();
		Rocket r = sim.getRocket();
		FlightConfigurationId fcid = sim.getFlightConfigurationId();
		StringBuilder b = new StringBuilder();
		b.append(r.getModID()).append('|').append(r.getFlightConfiguration(fcid).getModID()).append('|').append(o.getRandomSeed())
				.append('|').append(o.getLaunchIntoWind()).append('|').append(AeroTable.stamp(r));
		for (var ext : sim.getSimulationExtensions()) { // an extension added or changed flies again
			b.append("|x:").append(ext.getId()).append(Extensions.configText(ext));
		}
		// OpenRocket's deployment, motor and separation settings change without a change event, so no counter moves.
		for (RocketComponent c : r) {
			if (c instanceof RecoveryDevice rd) {
				var dc = rd.getDeploymentConfigurations().get(fcid);
				b.append('|').append(dc.getDeployEvent()).append(dc.getDeployAltitude()).append('/').append(dc.getDeployDelay());
			}
			if (c instanceof MotorMount mm && mm.isMotorMount()) {
				MotorConfiguration mc = mm.getMotorConfig(fcid);
				b.append('|').append(mc.getMotor() == null ? "-" : mc.getMotor().getDigest()).append(mc.getEjectionDelay())
						.append(mc.getIgnitionEvent()).append(mc.getIgnitionDelay());
			}
			if (c instanceof AxialStage st) {
				var sc = st.getSeparationConfigurations().get(fcid);
				b.append('|').append(sc.getSeparationEvent()).append(sc.getSeparationDelay());
			}
		}
		return b.toString();
	}

	/**
	 * The simulation's results, flown only if they are out of date. Most tools read the same flight (run, check,
	 * recovery, card, electronics...); flying it again for each one was most of their time. Results are reused only
	 * when OpenRocket says they are current (same configuration and launch conditions) and the run was a plain one
	 * of this server (same seed, launch-into-wind and aero table, no extra listeners).
	 */
	public static FlightData ensure(Simulation sim) {
		FlightData data = sim.getSimulatedData();
		String now = stamp(sim);
		if (data != null && data.getBranchCount() > 0 && now.equals(FLOWN.get(sim))
				&& sim.getStatus() == Simulation.Status.UPTODATE) {
			return data;
		}
		FlightData out = run(sim);
		FLOWN.put(sim, stamp(sim));
		return out;
	}

	private static final java.lang.reflect.Field DOCUMENT;

	static {
		java.lang.reflect.Field f = null;
		try {
			f = Simulation.class.getDeclaredField("document");
			f.setAccessible(true);
		} catch (ReflectiveOperationException | RuntimeException e) {
			// another OpenRocket version: re-flown results then mark the design edited, as OpenRocket itself does
		}
		DOCUMENT = f;
	}

	/** The design a simulation belongs to (OpenRocket keeps it private), or null. */
	static OpenRocketDocument documentOf(Simulation sim) {
		try {
			return DOCUMENT == null ? null : (OpenRocketDocument) DOCUMENT.get(sim);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	public static FlightData run(Simulation sim, SimulationListener... listeners) {
		FLOWN.remove(sim); // a run with other listeners (or a failed one) is not reusable
		// Turbulence always follows the simulation's random seed, so every tool (run, check, optimize, sweep) sees the
		// same gusts for the same simulation. See Variants.seed.
		Variants.seed(sim.getOptions(), sim.getOptions().getRandomSeed());
		Analysis.settle(sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId()));
		var table = AeroTable.listenerFor(sim);
		int extra = table == null ? 1 : 2;
		listeners = Arrays.copyOf(listeners, listeners.length + extra);
		listeners[listeners.length - 1] = new Watchdog();
		if (table != null) {
			listeners[listeners.length - 2] = table;
		}
		FastSim.install(sim);
		// Flying is not an edit: refreshing a simulation's results, or flying a variant of it (the wind case, Monte
		// Carlo, a sweep), leaves a saved design saved, so opening a file and checking it does not mark it changed
		// and Quit does not ask about it. (Calls on a design run one at a time, so nothing else edits it meanwhile.)
		OpenRocketDocument doc = documentOf(sim);
		boolean saved = doc != null && doc.isSaved();
		try {
			sim.simulate(listeners);
		} catch (Exception e) {
			String why = String.valueOf(e.getMessage());
			if (why.contains("not-a-number") || why.contains("NaN")) {
				// OpenRocket's wording ("BUG: ... please report a bug") blames itself for what is nearly always an input.
				throw new ToolException("Simulation '" + sim.getName() + "' broke down numerically (a value became NaN). "
						+ "This comes from a non-physical rocket or launch: a part with zero or negative size or mass, a "
						+ "motor heavier than its thrust can lift, or extreme wind or launch settings. Check the last change "
						+ "(undo reverts it) and get_design for odd masses or lengths.", e);
			}
			throw new ToolException("Simulation '" + sim.getName() + "' failed: " + why, e);
		} finally {
			if (saved) {
				doc.setSaved(true);
			}
		}
		FlightData data = sim.getSimulatedData();
		if (data == null || data.getBranchCount() == 0) {
			throw new ToolException("Simulation produced no data.");
		}
		return data;
	}

	// ------------------------------------------------------------------------------------------- extraction

	static double at(FlightDataBranch b, FlightDataType type, double time) {
		return Branch.of(b).at(type, time);
	}

	/**
	 * Tilt of the rocket axis from vertical. OpenRocket's orientationTheta is the elevation of the axis above the
	 * horizontal (atan2(z, hypot(x, y))), so tilt = 90 deg - theta.
	 */
	public static double tilt(FlightDataBranch b, double time) {
		return Math.PI / 2 - at(b, FlightDataType.TYPE_ORIENTATION_THETA, time);
	}

	/** Airspeed from Mach number and speed of sound (includes wind), falling back to ground speed. */
	static double airspeed(FlightDataBranch b, double time) {
		Branch br = Branch.of(b);
		int i = br.index(time);
		return i < 0 ? Double.NaN : br.airspeed(i);
	}

	public record Window(double min, double minTime, double max, double maxTime) {
	}

	/** Min and max of a variable over [t0, t1], ignoring points where airspeed is below {@code minAirspeed}. */
	static Window extremes(FlightDataBranch b, FlightDataType type, double t0, double t1, double minAirspeed) {
		Branch br = Branch.of(b);
		double[] vs = br.col(type);
		double min = Double.NaN, max = Double.NaN, tmin = Double.NaN, tmax = Double.NaN;
		int from = Math.max(0, br.index(t0));
		for (int i = from; i < br.size() && br.time[i] <= t1; i++) {
			double v = vs[i];
			if (br.time[i] < t0 || Double.isNaN(v) || (minAirspeed > 0 && !(br.airspeed(i) >= minAirspeed))) {
				continue;
			}
			if (Double.isNaN(min) || v < min) {
				min = v;
				tmin = br.time[i];
			}
			if (Double.isNaN(max) || v > max) {
				max = v;
				tmax = br.time[i];
			}
		}
		return new Window(min, tmin, max, tmax);
	}

	static double mean(FlightDataBranch b, FlightDataType type, double t0, double t1) {
		Branch br = Branch.of(b);
		double[] vs = br.col(type);
		double sum = 0;
		int n = 0;
		for (int i = Math.max(0, br.index(t0)); i < br.size() && br.time[i] <= t1; i++) {
			if (br.time[i] >= t0 && !Double.isNaN(vs[i])) {
				sum += vs[i];
				n++;
			}
		}
		return n == 0 ? Double.NaN : sum / n;
	}

	static double eventTime(FlightDataBranch b, FlightEvent.Type type) {
		FlightEvent e = b.getFirstEvent(type);
		return e == null ? Double.NaN : e.getTime();
	}

	/** One recovery-device deployment, all SI. */
	public record Deployment(String branch, RecoveryDevice device, double time, double timeAfterApogee,
			double altitudeAgl, double altitudeMsl, double airspeed, double verticalSpeed, double horizontalSpeed,
			double density, double mass, double steadyDescentRate, double descentWindowEnd) {
	}

	public static List<Deployment> deployments(Simulation sim) {
		List<Deployment> out = new ArrayList<>();
		FlightData data = sim.getSimulatedData();
		double launchAlt = sim.getOptions().getLaunchAltitude();
		for (FlightDataBranch b : data.getBranches()) {
			double apogee = eventTime(b, FlightEvent.Type.APOGEE);
			double end = eventTime(b, FlightEvent.Type.GROUND_HIT);
			if (Double.isNaN(end)) {
				end = Branch.of(b).last(FlightDataType.TYPE_TIME);
			}
			List<FlightEvent> deploys = new ArrayList<>();
			for (FlightEvent e : b.getEvents()) {
				if (e.getType() == FlightEvent.Type.RECOVERY_DEVICE_DEPLOYMENT && e.getSource() instanceof RecoveryDevice) {
					deploys.add(e);
				}
			}
			for (int i = 0; i < deploys.size(); i++) {
				FlightEvent e = deploys.get(i);
				double t = e.getTime();
				double next = end;
				for (int j = i + 1; j < deploys.size(); j++) {
					if (deploys.get(j).getTime() > t + 1e-6) {
						next = deploys.get(j).getTime();
						break;
					}
				}
				double w0 = t + 0.6 * (next - t);
				double w1 = next - 0.05 * (next - t);
				double descent = -mean(b, FlightDataType.TYPE_VELOCITY_Z, w0, w1);
				double alt = at(b, FlightDataType.TYPE_ALTITUDE, t);
				double density = at(b, FlightDataType.TYPE_AIR_DENSITY, t);
				if (Double.isNaN(density)) {
					density = Atmosphere.at(alt + launchAlt).density();
				}
				out.add(new Deployment(b.getName(), (RecoveryDevice) e.getSource(), t,
						Double.isNaN(apogee) ? Double.NaN : t - apogee, alt, alt + launchAlt, airspeed(b, t),
						at(b, FlightDataType.TYPE_VELOCITY_Z, t), at(b, FlightDataType.TYPE_VELOCITY_XY, t), density,
						at(b, FlightDataType.TYPE_MASS, t), descent, next));
			}
		}
		return out;
	}

	public static Map<String, Object> renderDeployment(Deployment d) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("branch", d.branch());
		m.put("device", d.device().getName());
		m.put("deviceId", Components.shortId(d.device()));
		m.put("cd", Units.num(d.device().getCD()));
		m.put("cdA", Units.fmt(d.device().getCD() * d.device().getArea(), Dim.AREA));
		m.put("time", Units.fmt(d.time(), Dim.TIME));
		if (!Double.isNaN(d.timeAfterApogee())) {
			m.put("timeAfterApogee", Units.fmt(d.timeAfterApogee(), Dim.TIME));
		}
		m.put("altitudeAGL", Units.fmt(d.altitudeAgl(), Dim.DISTANCE));
		m.put("airspeedAtDeployment", Units.fmt(d.airspeed(), Dim.VELOCITY));
		m.put("verticalVelocity", Units.fmt(d.verticalSpeed(), Dim.VELOCITY));
		m.put("horizontalGroundSpeed", Units.fmt(d.horizontalSpeed(), Dim.VELOCITY));
		m.put("airDensity", Units.fmt(d.density(), Dim.DENSITY));
		m.put("massDecelerated", Units.fmt(d.mass(), Dim.MASS));
		m.put("steadyDescentRate", Units.fmt(d.steadyDescentRate(), Dim.VELOCITY));
		return m;
	}

	/** Motor ignitions in the sustainer branch with thrust-to-weight and tilt at ignition. */
	static List<Map<String, Object>> ignitions(Simulation sim, FlightDataBranch b) {
		List<Map<String, Object>> out = new ArrayList<>();
		FlightConfigurationId fcid = sim.getFlightConfigurationId();
		for (FlightEvent e : b.getEvents()) {
			if (e.getType() != FlightEvent.Type.IGNITION || !(e.getSource() instanceof MotorMount mount)) {
				continue;
			}
			MotorConfiguration mc = mount.getMotorConfig(fcid);
			Motor motor = mc == null ? null : mc.getMotor();
			double t = e.getTime();
			double mass = at(b, FlightDataType.TYPE_MASS, t);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("mount", ((RocketComponent) mount).getName());
			m.put("stage", ((RocketComponent) mount).getStage().getName());
			m.put("time", Units.fmt(t, Dim.TIME));
			if (motor != null) {
				int count = mc.getMotorCount();
				m.put("motor", (count > 1 ? count + " x " : "") + motor.getDesignation());
				double avgThrust = motor instanceof ThrustCurveMotor tc
						? tc.getAverageThrustEstimate() : Double.NaN;
				m.put("averageThrustToWeight", Units.num(count * avgThrust / (mass * Atmosphere.G0)));
			}
			m.put("vehicleMassAtIgnition", Units.fmt(mass, Dim.MASS));
			if (t > 0.01) {
				m.put("altitudeAtIgnition", Units.fmt(at(b, FlightDataType.TYPE_ALTITUDE, t), Dim.DISTANCE));
				m.put("tiltFromVerticalAtIgnition", Units.fmt(tilt(b, t), Dim.ANGLE));
				m.put("velocityAtIgnition", Units.fmt(at(b, FlightDataType.TYPE_VELOCITY_TOTAL, t), Dim.VELOCITY));
			}
			out.add(m);
		}
		return out;
	}

	/**
	 * Headline numbers for comparing runs (sweeps, optimization, Monte Carlo): stability is taken between rail exit
	 * and apogee while airspeed exceeds 100 ft/s, where the static margin is meaningful.
	 */
	public static Map<String, Object> flightMetrics(FlightData data) {
		FlightDataBranch b = data.getBranch(0);
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("apogee", Units.fmt(data.getMaxAltitude(), Dim.DISTANCE));
		row.put("railExitVelocity", Units.fmt(data.getLaunchRodVelocity(), Dim.VELOCITY));
		row.put("maxMach", Units.num(data.getMaxMachNumber()));
		Window w = ascentStability(b);
		if (w != null) {
			row.put("minStability", Units.num(w.min()) + " cal");
			row.put("maxStability", Units.num(w.max()) + " cal");
		}
		List<String> landings = new ArrayList<>();
		for (FlightDataBranch fb : data.getBranches()) {
			if (fb.getFirstEvent(FlightEvent.Type.GROUND_HIT) != null) {
				landings.add(fb.getName() + " " + Units.fmt(Branch.of(fb).last(FlightDataType.TYPE_POSITION_XY), Dim.DISTANCE));
			}
		}
		row.put("landingDistance", landings);
		return row;
	}

	/**
	 * Ascent stability window (rail exit to apogee, airspeed > 100 ft/s); null without those events, or when the rocket never
	 * flies that fast.
	 */
	public static Window ascentStability(FlightDataBranch b) {
		double rail = eventTime(b, FlightEvent.Type.LAUNCHROD);
		double apogee = eventTime(b, FlightEvent.Type.APOGEE);
		if (Double.isNaN(rail) || Double.isNaN(apogee)) {
			return null;
		}
		Window w = extremes(b, FlightDataType.TYPE_STABILITY, rail, apogee, 30.48);
		return Double.isNaN(w.min()) ? null : w;
	}

	/** Compact flight summary for the model: key numbers per branch (stage). */
	public static Map<String, Object> summarize(Simulation sim) {
		FlightData data = sim.getSimulatedData();
		SimulationOptions opt = sim.getOptions();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("simulation", sim.getName());
		FlightConfiguration fc = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId());
		out.put("flightConfiguration", fc.getName());
		Map<String, Object> cond = new LinkedHashMap<>();
		cond.put("launchRodLength", Units.fmt(opt.getLaunchRodLength(), Dim.LENGTH));
		cond.put("launchRodAngleFromVertical", Units.fmt(opt.getLaunchRodAngle(), Dim.ANGLE));
		if (Winds.isMultiLevel(opt)) {
			cond.put("wind", "multi-level profile, " + Units.fmt(Winds.speed(opt), Dim.VELOCITY) + " at the ground (wind_profile show)");
		} else {
			cond.put("windSpeedAverage", Units.fmt(Winds.speed(opt), Dim.VELOCITY));
		}
		cond.put("launchSiteAltitude", Units.fmt(opt.getLaunchAltitude(), Dim.DISTANCE));
		out.put("conditions", cond);
		List<String> problems = problems(sim);
		if (!problems.isEmpty()) {
			out.put("PROBLEMS", problems); // first, so it is read before the numbers it puts in doubt
		}

		FlightDataBranch main = data.getBranch(0);
		Map<String, Object> overall = new LinkedHashMap<>();
		overall.put("apogee", Units.fmt(data.getMaxAltitude(), Dim.DISTANCE));
		overall.put("timeToApogee", Units.fmt(data.getTimeToApogee(), Dim.TIME));
		overall.put("maxVelocity", Units.fmt(data.getMaxVelocity(), Dim.VELOCITY));
		overall.put("maxMach", Units.num(data.getMaxMachNumber()));
		overall.put("maxAcceleration", Units.fmt(data.getMaxAcceleration(), Dim.ACCELERATION)
				+ " = " + Units.num(data.getMaxAcceleration() / Atmosphere.G0) + " G");
		overall.put("railExitVelocity", Units.fmt(data.getLaunchRodVelocity(), Dim.VELOCITY));
		double rail = eventTime(main, FlightEvent.Type.LAUNCHROD);
		double apogee = eventTime(main, FlightEvent.Type.APOGEE);
		if (!Double.isNaN(rail)) {
			overall.put("thrustToWeightOnRail(avg)", Units.num(mean(main, FlightDataType.TYPE_THRUST_WEIGHT_RATIO, 0, rail)));
			overall.put("stabilityAtRailExit", Units.num(at(main, FlightDataType.TYPE_STABILITY, rail)) + " cal");
		}
		if (!Double.isNaN(rail) && !Double.isNaN(apogee)) {
			Window s = extremes(main, FlightDataType.TYPE_STABILITY, rail, apogee, 0);
			Window fast = extremes(main, FlightDataType.TYPE_STABILITY, rail, apogee, 30.48);
			overall.put("minStabilityDuringAscent", Units.num(s.min()) + " cal at t=" + Units.num(s.minTime()) + " s (Mach "
					+ Units.num(at(main, FlightDataType.TYPE_MACH_NUMBER, s.minTime())) + ")");
			overall.put("minStabilityAbove100ftps", Units.num(fast.min()) + " cal at t=" + Units.num(fast.minTime()) + " s (Mach "
					+ Units.num(at(main, FlightDataType.TYPE_MACH_NUMBER, fast.minTime())) + ")");
			overall.put("maxStabilityDuringAscent", Units.num(s.max()) + " cal at t=" + Units.num(s.maxTime()) + " s");
		}
		overall.put("optimumEjectionDelay", Units.fmt(data.getOptimumDelay(), Dim.TIME));
		out.put("flight", overall);
		out.put("ignitions", ignitions(sim, main));

		List<Map<String, Object>> branches = new ArrayList<>();
		for (FlightDataBranch b : data.getBranches()) {
			Map<String, Object> bm = new LinkedHashMap<>();
			bm.put("branch", b.getName());
			bm.put("apogee", Units.fmt(b.getMaximum(FlightDataType.TYPE_ALTITUDE), Dim.DISTANCE));
			double gh = eventTime(b, FlightEvent.Type.GROUND_HIT);
			if (!Double.isNaN(gh)) {
				bm.put("groundHitVelocity", Units.fmt(Math.abs(at(b, FlightDataType.TYPE_VELOCITY_TOTAL, gh)), Dim.VELOCITY));
				bm.put("landingDistanceFromPad", Units.fmt(at(b, FlightDataType.TYPE_POSITION_XY, gh), Dim.DISTANCE));
				bm.put("flightTime", Units.fmt(gh, Dim.TIME));
				double ap = eventTime(b, FlightEvent.Type.APOGEE);
				if (!Double.isNaN(ap)) {
					bm.put("descentTime", Units.fmt(gh - ap, Dim.TIME));
				}
				bm.put("landingMass", Units.fmt(at(b, FlightDataType.TYPE_MASS, gh), Dim.MASS));
			}
			List<String> events = new ArrayList<>();
			for (FlightEvent e : b.getEvents()) {
				if (e.getType() == FlightEvent.Type.ALTITUDE || e.getType() == FlightEvent.Type.SIMULATION_END) {
					continue;
				}
				events.add(String.format(Locale.ROOT, "t=%s s %s%s at %s", Units.num(e.getTime()), e.getType(),
						e.getSource() == null || e.getSource() instanceof Rocket ? "" : " (" + e.getSource().getName() + ")",
						Units.fmt(at(b, FlightDataType.TYPE_ALTITUDE, e.getTime()), Dim.DISTANCE)));
			}
			bm.put("events", events);
			branches.add(bm);
		}
		out.put("branches", branches);
		List<Map<String, Object>> deps = new ArrayList<>();
		for (Deployment d : deployments(sim)) {
			deps.add(renderDeployment(d));
		}
		out.put("deployments", deps);
		List<String> warnings = new ArrayList<>();
		for (Warning w : data.getWarningSet()) {
			warnings.add(w.toString());
		}
		if (!warnings.isEmpty()) {
			out.put("warnings", warnings);
		}
		return out;
	}

	/** Above this Mach OpenRocket's empirical aerodynamics are an extrapolation. */
	static final double MACH_LIMIT = 3;
	/** Peak acceleration (G) beyond what student airframes, motors and electronics are built for. */
	static final double G_LIMIT = 150;

	/**
	 * Flights that are not physically believable, in plain words: OpenRocket stopped the simulation (no lift-off,
	 * tumbling or a parachute out under thrust), or the numbers are outside what the models or any student rocket can
	 * do. Empty for a sound flight.
	 */
	public static List<String> problems(Simulation sim) {
		List<String> out = new ArrayList<>();
		FlightData data = sim.getSimulatedData();
		if (data == null) {
			return out;
		}
		for (FlightDataBranch b : data.getBranches()) {
			for (FlightEvent e : b.getEvents()) {
				if (e.getType() == FlightEvent.Type.SIM_ABORT) {
					String why = e.getData() instanceof info.openrocket.core.logging.SimulationAbort a
							? abortAdvice(a.getCause()) : "OpenRocket stopped the simulation";
					out.add(b.getName() + ": simulation stopped at t=" + Units.num(e.getTime()) + " s: " + why);
				}
			}
		}
		// OpenRocket's own warnings from the run (large angle of attack, a gap in the airframe, fins thicker than the
		// model allows...), worded as it words them. A tumble it also reports as the abort above. Its high-speed
		// deployment warning (any device above 20 m/s, so nearly every drogue at apogee) is left to recovery_analysis,
		// which judges each opening load against the team's standards.
		boolean aborted = !out.isEmpty();
		info.openrocket.core.logging.WarningSet ws = data.getWarningSet();
		if (ws != null) {
			java.util.Set<String> seen = new java.util.HashSet<>();
			for (info.openrocket.core.logging.Warning w : ws) {
				if (w.getPriority() == info.openrocket.core.logging.MessagePriority.LOW
						|| w instanceof info.openrocket.core.logging.Warning.HighSpeedDeployment
						|| aborted && w.equals(info.openrocket.core.logging.Warning.TUMBLE_UNDER_THRUST)) {
					continue;
				}
				String text = w.toString().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
				if (!text.isEmpty() && seen.add(text)) {
					out.add("OpenRocket warns: " + text + (text.endsWith(".") ? "" : "."));
				}
			}
		}
		double mach = data.getMaxMachNumber();
		if (mach > MACH_LIMIT) {
			out.add("Max Mach " + Units.num(mach) + " is beyond OpenRocket's aerodynamics (validated to about Mach "
					+ Units.num(MACH_LIMIT) + "): drag, stability and heating are extrapolated. Check the motor's thrust and mass.");
		}
		double g = data.getMaxAcceleration() / Atmosphere.G0;
		if (g > G_LIMIT) {
			out.add("Peak acceleration " + Units.num(g) + " G is beyond what student airframes, motors and electronics are "
					+ "built for: check the motor's thrust curve (N, not lbf or kN) and the rocket's mass.");
		}
		return out;
	}

	private static String abortAdvice(info.openrocket.core.logging.SimulationAbort.Cause c) {
		return switch (c) {
			case NO_LIFTOFF -> "the motor burned out without lifting the rocket off the pad. Thrust-to-weight is below 1: "
					+ "use a bigger motor or take mass out (get_design shows the masses).";
			case TUMBLE_UNDER_THRUST -> "the rocket tumbled while the motor was burning (unstable or overpowered). Check "
					+ "stability (ballast, fins) and the motor.";
			case DEPLOY_UNDER_THRUST -> "a parachute opened while the motor was still burning. Check set_deployment "
					+ "(event and delay) and the motor's ejection delay.";
			case NO_MOTORS_DEFINED, NO_CONFIGURED_IGNITION, NO_MOTORS_FIRED -> "no motor fired. Use set_motor.";
			case ACTIVE_LENGTH_ZERO, ACTIVE_MASS_ZERO, NO_ACTIVE_STAGES -> "the flying stage has no length or mass. Check "
					+ "the parts with get_design.";
			case NO_CP -> "OpenRocket could not find the centre of pressure (no aerodynamic surfaces?). Check the parts.";
			default -> c.toString().replaceAll("<[^>]+>", " ").trim();
		};
	}

	// ------------------------------------------------------------------------------------------ flight data

	private static final Map<String, FlightDataType> TYPES = new LinkedHashMap<>();

	static {
		for (Field f : FlightDataType.class.getFields()) {
			if (Modifier.isStatic(f.getModifiers()) && f.getType() == FlightDataType.class && f.getName().startsWith("TYPE_")) {
				try {
					TYPES.put(key(f.getName().substring(5)), (FlightDataType) f.get(null));
				} catch (IllegalAccessException ignored) {
					// skip
				}
			}
		}
	}

	private static String key(String s) {
		return s.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "").replace("-", "");
	}

	public static List<String> variableNames() {
		List<String> names = new ArrayList<>();
		for (String k : TYPES.keySet()) {
			names.add(k);
		}
		return names;
	}

	public static FlightDataType type(String name) {
		FlightDataType t = TYPES.get(key(name.replaceFirst("(?i)^type_", "")));
		if (t == null) {
			throw new ToolException("Unknown flight variable '" + name + "'. Available: " + variableNames());
		}
		return t;
	}

	public static Dim dimOf(UnitGroup g) {
		if (g == UnitGroup.UNITS_DISTANCE) {
			return Dim.DISTANCE;
		}
		if (g == UnitGroup.UNITS_LENGTH) {
			return Dim.LENGTH;
		}
		if (g == UnitGroup.UNITS_VELOCITY || g == UnitGroup.UNITS_WINDSPEED) {
			return Dim.VELOCITY;
		}
		if (g == UnitGroup.UNITS_ACCELERATION) {
			return Dim.ACCELERATION;
		}
		if (g == UnitGroup.UNITS_MASS) {
			return Dim.MASS;
		}
		if (g == UnitGroup.UNITS_FORCE) {
			return Dim.FORCE;
		}
		if (g == UnitGroup.UNITS_ANGLE) {
			return Dim.ANGLE;
		}
		if (g == UnitGroup.UNITS_DENSITY_BULK) {
			return Dim.DENSITY;
		}
		if (g == UnitGroup.UNITS_PRESSURE) {
			return Dim.PRESSURE;
		}
		if (g == UnitGroup.UNITS_TEMPERATURE) {
			return Dim.TEMPERATURE;
		}
		if (g == UnitGroup.UNITS_LONG_TIME || g == UnitGroup.UNITS_TIME_STEP || g == UnitGroup.UNITS_SHORT_TIME) {
			return Dim.TIME;
		}
		return null;
	}

	/** Time series, down-sampled to at most {@code maxPoints} rows, converted to the display unit system. */
	public static Map<String, Object> series(Simulation sim, String branchRef, List<String> variables, int maxPoints,
			double t0, double t1) {
		FlightData data = sim.getSimulatedData();
		FlightDataBranch b = null;
		if (branchRef == null || branchRef.isBlank()) {
			b = data.getBranch(0);
		} else {
			for (FlightDataBranch fb : data.getBranches()) {
				if (fb.getName().equalsIgnoreCase(branchRef)) {
					b = fb;
				}
			}
			if (b == null) {
				try {
					b = data.getBranch(Integer.parseInt(branchRef));
				} catch (RuntimeException e) {
					throw new ToolException("No branch '" + branchRef + "'.");
				}
			}
		}
		List<FlightDataType> types = new ArrayList<>();
		types.add(FlightDataType.TYPE_TIME);
		for (String v : variables) {
			FlightDataType t = type(v);
			if (t != FlightDataType.TYPE_TIME) {
				types.add(t);
			}
		}
		Branch br = Branch.of(b);
		double[] ts = br.time;
		List<Integer> idx = new ArrayList<>();
		for (int i = 0; i < ts.length; i++) {
			double t = ts[i];
			if ((Double.isNaN(t0) || t >= t0) && (Double.isNaN(t1) || t <= t1)) {
				idx.add(i);
			}
		}
		int n = idx.size();
		int points = Math.max(2, Math.min(maxPoints, n));
		List<String> columns = new ArrayList<>();
		String[] units = new String[types.size()];
		for (int c = 0; c < types.size(); c++) {
			FlightDataType t = types.get(c);
			Dim d = dimOf(t.getUnitGroup());
			String unit = d == null ? t.getUnitGroup().getSIUnit().getUnit().replace("\u200b", "").trim()
					: Units.system() == UnitSystem.IMPERIAL ? d.imperial : d.metric;
			units[c] = d == null ? null : unit;
			columns.add(t.getName() + (unit == null || unit.isBlank() ? "" : " [" + unit + "]"));
		}
		List<List<Object>> rows = new ArrayList<>();
		for (int k = 0; k < points && n > 0; k++) {
			int i = idx.get((int) Math.round((double) k * (n - 1) / Math.max(1, points - 1)));
			List<Object> row = new ArrayList<>();
			for (int c = 0; c < types.size(); c++) {
				double[] col = br.col(types.get(c));
				double v = i < col.length ? col[i] : Double.NaN;
				if (Double.isNaN(v)) {
					row.add(null);
				} else {
					double shown = units[c] == null ? v : Units.fromSi(v, units[c]);
					row.add(Double.parseDouble(Units.num(shown)));
				}
			}
			rows.add(row);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("branch", b.getName());
		out.put("columns", columns);
		out.put("rows", rows);
		out.put("totalPoints", n);
		return out;
	}
}
