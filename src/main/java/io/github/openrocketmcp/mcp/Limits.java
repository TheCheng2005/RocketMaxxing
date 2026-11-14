package io.github.openrocketmcp.mcp;

import java.util.HashMap;
import java.util.Map;

import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Physical limits on tool arguments, so a negative mass, a zero drag coefficient or a 10^30 m/s wind is refused with
 * a sentence instead of producing a nonsense answer (or a NaN deep inside OpenRocket). Rules are keyed by argument
 * name, which every tool shares ("cd", "windSpeed", "mass"...); arguments without a rule of their own get their
 * dimension's default (no negative masses, areas or volumes; nothing beyond any rocket's scale). Bounds are generous:
 * they catch typos and wrong units, not unusual designs.
 */
public final class Limits {
	private Limits() {
	}

	/** Allowed range; {@code open} ends exclude the bound itself. */
	record Rule(double min, boolean minOpen, double max, boolean maxOpen, Dim dim) {
		boolean ok(double v) {
			return (minOpen ? v > min : v >= min) && (maxOpen ? v < max : v <= max);
		}
	}

	private static final double DEG = Math.PI / 180;
	private static final Map<String, Rule> BY_NAME = new HashMap<>();
	private static final Map<Dim, Rule> BY_DIM = new HashMap<>();

	private static void name(Dim dim, double min, boolean minOpen, double max, String... names) {
		for (String n : names) {
			BY_NAME.put(n, new Rule(min, minOpen, max, false, dim));
		}
	}

	/** Greater than zero, up to max. */
	private static void positive(Dim dim, double max, String... names) {
		name(dim, 0, true, max, names);
	}

	/** Zero or more, up to max. */
	private static void nonNegative(Dim dim, double max, String... names) {
		name(dim, 0, false, max, names);
	}

	private static void dim(Dim dim, double min, double max) {
		BY_DIM.put(dim, new Rule(min, false, max, false, dim));
	}

	static {
		// Defaults per dimension: the sign rocketry gives each quantity, and far more than any student rocket.
		dim(Dim.MASS, 0, 1e5);
		dim(Dim.CHARGE_MASS, 0, 1);
		dim(Dim.LENGTH, 0, 1e3);
		dim(Dim.DISTANCE, -1e7, 1e7);
		dim(Dim.VELOCITY, 0, 1e4);
		dim(Dim.ACCELERATION, 0, 1e6);
		dim(Dim.FORCE, 0, 1e8);
		dim(Dim.AREA, 0, 1e4);
		dim(Dim.VOLUME, 0, 1e3);
		dim(Dim.PRESSURE, 0, 1e12);
		dim(Dim.ENERGY, 0, 1e12);
		dim(Dim.DENSITY, 0, 1e5);
		dim(Dim.TIME, 0, 1e7);
		dim(Dim.ANGLE, -720 * DEG, 720 * DEG);
		dim(Dim.IMPULSE, 0, 1e8);
		dim(Dim.TEMPERATURE, 1, 1e4);

		// Sizes that must exist.
		positive(Dim.MASS, 1e5, "mass", "totalMass", "propellantMass", "targetLaunchMass");
		positive(Dim.LENGTH, 1e3, "diameter", "length", "thickness", "thicknesses", "wallThickness", "outerDiameter",
				"bayDiameter", "bayLength", "couplerLength", "availableLength", "packedDiameter", "packedLength",
				"cordLength", "cordWidth", "cordThickness", "bulkheadThickness", "noseLengths", "noseTipRadius", "maxSpan",
				"maxRootChord", "maxLength", "maxDiameter", "maxOuterDiameter", "maxInnerDiameter");
		positive(Dim.LENGTH, 100, "railLength", "groundStationHeight");
		name(Dim.LENGTH, -10, false, 10, "overhang", "maxOverhang"); // negative = motor recessed in the mount
		positive(Dim.AREA, 1e4, "area");
		nonNegative(Dim.AREA, 1e4, "otherCdA");
		positive(Dim.VOLUME, 1e3, "bayVolume", "glppVolume", "packedVolume", "availableVolume");
		positive(Dim.FORCE, 1e8, "holdForce", "pinStrength", "averageThrust");
		positive(Dim.PRESSURE, 1e12, "meop", "burstPressure", "pressure", "proofPressure", "ultimateStrength",
				"allowableStress", "shearModulus", "youngsModulus");
		positive(Dim.ENERGY, 1e12, "energyLimit");
		positive(Dim.IMPULSE, 1e8, "totalImpulse", "vehicleTotalImpulse");
		positive(Dim.TIME, 1e4, "burnTime");
		nonNegative(Dim.TIME, 1e5, "delay", "delays", "ejectionDelay", "ignitionDelay", "padWait", "recoveryTime", "tStart",
				"tEnd");
		nonNegative(Dim.TIME, 3600, "padTime", "restTime"); // logged sensor data: an hour already makes a huge file

		// Engines (simulate_engine).
		positive(Dim.LENGTH, 10, "throatDiameter", "exitDiameter", "grainOuterDiameter", "grainCoreDiameter", "grainLength",
				"portDiameter", "injectorHoleDiameter");
		positive(Dim.MASS, 1e5, "oxidizerMass", "fuelMass", "dryMass");
		positive(Dim.PRESSURE, 1e9, "chamberPressure", "tankPressure", "tankPressureEnd");
		positive(Dim.DENSITY, 3e4, "propellantDensity", "fuelDensity", "oxidizerDensity");
		name(Dim.VELOCITY, 200, false, 5000, "cstar");
		name(Dim.DIMENSIONLESS, 1.05, false, 1.7, "gamma");
		name(Dim.DIMENSIONLESS, 1, false, 500, "expansionRatio");
		name(Dim.DIMENSIONLESS, 0.3, false, 1, "cstarEfficiency", "nozzleEfficiency");
		positive(Dim.DIMENSIONLESS, 1, "dischargeCoefficient");
		name(Dim.DIMENSIONLESS, 0.01, false, 0.95, "ullageFraction");
		name(Dim.DIMENSIONLESS, 1, false, 1.7, "polytropicExponent");
		positive(Dim.DIMENSIONLESS, 100, "mixtureRatio", "oxidizerFlow", "burnRateCoefficient", "regressionCoefficient");
		name(Dim.DIMENSIONLESS, 0, false, 0.99, "burnRateExponent");
		name(Dim.DIMENSIONLESS, 0, false, 1.5, "regressionExponent");

		// Air and flight.
		positive(Dim.DENSITY, 5, "airDensity");
		name(Dim.TEMPERATURE, 173.15, false, 373.15, "temperature"); // -100 to +100 C at the launch site
		nonNegative(Dim.VELOCITY, 150, "windSpeed", "groundSpeed", "speed", "gustSpeed", "crosswind");
		nonNegative(Dim.VELOCITY, 50, "windSpeedSd", "sd");
		positive(Dim.VELOCITY, 340, "descentRate", "targetDescentRate");
		nonNegative(Dim.VELOCITY, 340, "minRailExit"); // 0 switches the constraint off
		positive(Dim.VELOCITY, 3000, "velocity");
		name(Dim.DISTANCE, -500, false, 1e4, "launchSiteAltitude");
		name(Dim.DISTANCE, -500, false, 1e5, "altitudeMsl", "altitude");
		nonNegative(Dim.DISTANCE, 1e5, "altitudeAgl", "landingDispersion");
		positive(Dim.DISTANCE, 2e5, "targetApogee", "minApogee", "targetAltitude", "actualAltitude", "predictedAltitude", "top");
		nonNegative(Dim.ANGLE, 60 * DEG, "launchAngle");
		nonNegative(Dim.ANGLE, 180 * DEG, "launchAngleSd", "launchDirectionSd", "windDirectionSd");
		name(Dim.ANGLE, -90 * DEG, false, 90 * DEG, "angleOfAttack", "pathAngle");
		name(Dim.ANGLE, -30 * DEG, false, 30 * DEG, "cantAngles");
		name(Dim.ANGLE, -720 * DEG, false, 720 * DEG, "direction", "windDirection", "launchDirection");
		name(Dim.DIMENSIONLESS, -720, false, 720, "azimuth");
		nonNegative(Dim.FORCE, 1e8, "extraForce");
		nonNegative(Dim.ANGLE, 85 * DEG, "maxSweepAngle");
		name(Dim.DIMENSIONLESS, -90, false, 90, "latitude", "siteLatitude", "elevation");
		name(Dim.DIMENSIONLESS, -180, false, 360, "longitude", "siteLongitude");

		// Coefficients and factors.
		positive(Dim.DIMENSIONLESS, 5, "cd");
		positive(Dim.DIMENSIONLESS, 10, "cx", "inflationExponent");
		positive(Dim.DIMENSIONLESS, 100, "fillConstant");
		// (monte_carlo checks its own spreads, massSd etc., with a more specific message.)
		nonNegative(Dim.DIMENSIONLESS, 1, "tolerance", "contingency", "turbulence", "windTurbulence");
		positive(Dim.DIMENSIONLESS, 1, "derating", "exponent");
		name(Dim.DIMENSIONLESS, 1, false, 10, "weldKnockdown"); // divides the allowable stress (>= 1.2 by the edicts)
		name(Dim.DIMENSIONLESS, 1, false, 10, "packingFactor"); // packed volume multiplier
		name(Dim.DIMENSIONLESS, 0, false, 0.5, "poissonRatio");
		name(Dim.DIMENSIONLESS, 1, false, 100, "safetyFactor", "backupFactor");
		positive(Dim.DIMENSIONLESS, 30, "mach", "maxMach", "designMach", "breakdownMach");
		nonNegative(Dim.DIMENSIONLESS, 30, "minStability", "maxStability", "targetStability");
		nonNegative(Dim.DIMENSIONLESS, 100, "minFlutterMargin");
		positive(Dim.DIMENSIONLESS, 1e5, "maxRollRate");
		positive(Dim.DIMENSIONLESS, 500, "staticFireIsp");

		// Electronics.
		positive(Dim.DIMENSIONLESS, 1000, "voltage", "ematchResistance");
		positive(Dim.DIMENSIONLESS, 1e6, "capacityMah");
		nonNegative(Dim.DIMENSIONLESS, 1e5, "currentMa");
		nonNegative(Dim.DIMENSIONLESS, 1000, "internalResistance", "brownoutVoltage");
		positive(Dim.DIMENSIONLESS, 100, "allFireCurrent");
		positive(Dim.DIMENSIONLESS, 1e5, "frequencyMhz");
		name(Dim.DIMENSIONLESS, -50, false, 60, "txPowerDbm");
		name(Dim.DIMENSIONLESS, -30, false, 40, "txAntennaGainDbi", "rxAntennaGainDbi");
		name(Dim.DIMENSIONLESS, -200, false, 0, "rxSensitivityDbm");
		nonNegative(Dim.DIMENSIONLESS, 100, "lossesDb", "requiredMarginDb");

		// Counts and sizes of the work asked for.
		name(Dim.DIMENSIONLESS, 1, false, 1000, "count", "pinCount", "maxCandidates", "modes", "steps");
		name(Dim.DIMENSIONLESS, 1, false, 10000, "runs", "limit", "maxEvaluations");
		name(Dim.DIMENSIONLESS, 2, false, 1e6, "maxPoints");
		name(Dim.DIMENSIONLESS, 3, false, 1000, "segments");
		name(Dim.DIMENSIONLESS, 1, false, 60, "fps");
		positive(Dim.DIMENSIONLESS, 600, "duration");
		name(Dim.DIMENSIONLESS, 16, false, 8000, "width", "gifWidth");
		positive(Dim.DIMENSIONLESS, 1e5, "rate");
		positive(Dim.DIMENSIONLESS, 100, "gpsRate");
		name(Dim.DIMENSIONLESS, 1, false, 10, "stages");
		name(Dim.DIMENSIONLESS, 1900, false, 2200, "yearFrom", "yearTo");
		nonNegative(Dim.DIMENSIONLESS, 1e6, "index");
	}

	/**
	 * Checks every top-level argument that has a rule of its own before the tool runs, so a nonsense value is refused
	 * even on a path that would not read it (a target apogee of 10^30 m with another objective). Values in another
	 * dimension are left for the tool, which names the unit it expected.
	 */
	public static void precheck(com.google.gson.JsonObject args, com.google.gson.JsonObject schema) {
		com.google.gson.JsonObject props = schema != null && schema.has("properties") ? schema.getAsJsonObject("properties") : null;
		for (Map.Entry<String, com.google.gson.JsonElement> e : args.entrySet()) {
			// Counts declared as integers are whole numbers, read or not.
			com.google.gson.JsonElement type = props != null && props.has(e.getKey()) && props.get(e.getKey()).isJsonObject()
					? props.getAsJsonObject(e.getKey()).get("type") : null;
			if (type != null && type.isJsonPrimitive() && "integer".equals(type.getAsString()) && e.getValue().isJsonPrimitive()
					&& e.getValue().getAsJsonPrimitive().isNumber()
					&& e.getValue().getAsDouble() != Math.rint(e.getValue().getAsDouble())) {
				throw new ToolException("Argument '" + e.getKey() + "' must be a whole number, got " + e.getValue() + ".");
			}
			shape(e.getKey(), type, e.getValue());
			Rule r = BY_NAME.get(e.getKey());
			if (r == null || !e.getValue().isJsonPrimitive() || e.getValue().getAsJsonPrimitive().isBoolean()) {
				continue;
			}
			double v;
			Dim dim = r.dim();
			if (e.getValue().getAsJsonPrimitive().isNumber()) {
				v = e.getValue().getAsDouble();
			} else {
				Units.Parsed p;
				try {
					p = Units.parse(e.getValue().getAsString());
				} catch (IllegalArgumentException notANumber) {
					continue; // the tool reports what it could not read
				}
				if (p.dim() != null && !same(r.dim(), p.dim())) {
					continue;
				}
				v = p.si();
			}
			check(e.getKey(), dim, v);
		}
	}

	/**
	 * A value of the wrong shape for what the tool declares is refused here, read or not: text where a number or a
	 * quantity goes, a word where yes or no goes. A tool that ignores an input in one of its modes would otherwise run
	 * as if nothing had been typed, and the user would never learn the value was not used.
	 */
	private static void shape(String key, com.google.gson.JsonElement type, com.google.gson.JsonElement value) {
		if (type == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			return;
		}
		String text = value.getAsString().trim();
		boolean number = false, string = false, bool = false;
		for (com.google.gson.JsonElement t : type.isJsonArray() ? type.getAsJsonArray() : java.util.List.of(type)) {
			String name = t.isJsonPrimitive() ? t.getAsString() : "";
			number |= name.equals("number") || name.equals("integer");
			string |= name.equals("string");
			bool |= name.equals("boolean");
		}
		if (bool && !string && !text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
			throw new ToolException("'" + key + "' is yes or no (true or false), got '" + text + "'.").field(key);
		}
		if (!number || text.isEmpty()) {
			return;
		}
		try {
			Units.parse(text);
		} catch (IllegalArgumentException e) {
			throw new ToolException(string
					? "'" + key + "' must be a number, with a unit if you like (e.g. \"20 ft/s\", \"4 in\"), got '" + text + "'."
					: "'" + key + "' must be a number, got '" + text + "'.").field(key);
		}
	}

	/** {@code v} (SI) checked against the rule for {@code key}, or its dimension's default. */
	public static double check(String key, Dim dim, double v) {
		if (!Double.isFinite(v)) {
			throw new ToolException("'" + key + "' must be a finite number (got " + v + ").");
		}
		// A name rule holds only for the quantity it was written for: "width" is pixels for an image but a length for a
		// shock cord, and the length gets the LENGTH default instead.
		Rule r = BY_NAME.get(key);
		if (r != null && !same(r.dim(), dim)) {
			r = null;
		}
		if (r == null && dim != null) {
			r = BY_DIM.get(dim);
		}
		if (r != null && !r.ok(v)) {
			throw new ToolException("'" + key + "' = " + show(v, r.dim()) + " is outside the physical range "
					+ (r.minOpen() ? "(" : "[") + show(r.min(), r.dim()) + ", " + show(r.max(), r.dim())
					+ (r.maxOpen() ? ")" : "]") + ". Check the value and its unit.");
		}
		return v;
	}

	private static boolean same(Dim rule, Dim value) {
		Dim a = rule == null ? Dim.DIMENSIONLESS : rule, b = value == null ? Dim.DIMENSIONLESS : value;
		return a == b || family(a) == family(b);
	}

	private static Dim family(Dim d) {
		return d == Dim.DISTANCE ? Dim.LENGTH : d == Dim.CHARGE_MASS ? Dim.MASS : d;
	}

	private static String show(double v, Dim dim) {
		return dim == null || dim == Dim.DIMENSIONLESS ? Units.num(v) : Units.fmt(v, dim);
	}

	/** True when {@code key} has a rule of its own (tests). */
	public static boolean hasRule(String key) {
		return BY_NAME.containsKey(key);
	}
}
