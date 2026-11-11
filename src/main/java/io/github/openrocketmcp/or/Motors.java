package io.github.openrocketmcp.or;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.database.motor.ThrustCurveMotorSet;
import info.openrocket.core.database.motor.ThrustCurveMotorSetDatabase;
import info.openrocket.core.file.motor.GeneralMotorLoader;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.ThrustCurveMotor;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.report.Xml;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Motor database search, lookup, import and custom (liquid / hybrid / research) motor creation.
 */
public final class Motors {

	/**
	 * Default ejection delay for a motor: its longest listed delay, or plugged (OpenRocket's PLUGGED_DELAY) when it lists
	 * none. Some catalogue entries list NaN for "plugged"; a NaN delay makes OpenRocket's simulation fail.
	 */
	public static double defaultDelay(Motor m) {
		double best = Double.NaN;
		double[] delays = m instanceof ThrustCurveMotor t ? t.getStandardDelays() : null;
		if (delays != null) {
			for (double d : delays) {
				if (Double.isFinite(d) && d >= 0 && (Double.isNaN(best) || d > best)) {
					best = d;
				}
			}
		}
		return Double.isNaN(best) ? Motor.PLUGGED_DELAY : best;
	}
	private Motors() {
	}

	/** Impulse class letter for a total impulse in N·s ("M", "N", ...). */
	public static String impulseClass(double totalImpulse) {
		if (totalImpulse <= 2.5) {
			return totalImpulse <= 0.3125 ? "1/8A" : totalImpulse <= 0.625 ? "1/4A" : totalImpulse <= 1.25 ? "1/2A" : "A";
		}
		int k = (int) Math.ceil(Math.log(totalImpulse / 2.5) / Math.log(2) - 1e-9);
		return k < 26 ? String.valueOf((char) ('A' + k)) : "beyond Z";
	}

	/** Upper total-impulse bound of an impulse class letter. */
	public static double classMaxImpulse(String letter) {
		String l = letter.trim().toUpperCase(Locale.ROOT).replaceFirst("^1/[248]", "");
		if (!l.matches("[A-Z]")) {
			throw new ToolException("Impulse class is one letter, A to Z (e.g. \"K\"), got '" + letter + "'.");
		}
		return 2.5 * Math.pow(2, l.charAt(0) - 'A');
	}

	/** Highest impulse class allowed for a certification level (NAR/TRA and CAR use the same letters). */
	public static String certMaxClass(String level) {
		return switch (level.trim().toUpperCase(Locale.ROOT).replace("LEVEL", "L").replace(" ", "")) {
			case "L1", "1" -> "I";
			case "L2", "2" -> "L";
			case "L3", "3" -> "O";
			default -> throw new ToolException("Certification level must be L1, L2 or L3.");
		};
	}

	public static Map<String, Object> describe(Motor motor) {
		Map<String, Object> m = new LinkedHashMap<>();
		if (!(motor instanceof ThrustCurveMotor t)) {
			m.put("designation", motor.getDesignation());
			return m;
		}
		m.put("designation", t.getDesignation());
		m.put("manufacturer", t.getManufacturer().getSimpleName());
		m.put("type", t.getMotorType().name().toLowerCase(Locale.ROOT));
		m.put("impulseClass", impulseClass(t.getTotalImpulseEstimate()));
		m.put("totalImpulse", Units.fmt(t.getTotalImpulseEstimate(), Dim.IMPULSE));
		m.put("averageThrust", Units.fmt(t.getAverageThrustEstimate(), Dim.FORCE));
		m.put("maxThrust", Units.fmt(t.getMaxThrustEstimate(), Dim.FORCE));
		m.put("burnTime", Units.fmt(t.getBurnTimeEstimate(), Dim.TIME));
		m.put("diameter", Units.fmt(t.getDiameter(), Dim.LENGTH));
		m.put("length", Units.fmt(t.getLength(), Dim.LENGTH));
		m.put("launchMass", Units.fmt(t.getLaunchMass(), Dim.MASS));
		m.put("propellantMass", Units.fmt(t.getLaunchMass() - t.getBurnoutMass(), Dim.MASS));
		double[] delays = t.getStandardDelays();
		if (delays != null && delays.length > 0) {
			List<String> d = new ArrayList<>();
			for (double v : delays) {
				d.add(Double.isNaN(v) || Double.isInfinite(v) ? "plugged" : Units.num(v));
			}
			m.put("standardDelays", d);
		}
		m.put("available", t.isAvailable());
		m.put("digest", t.getDigest());
		return m;
	}

	public record Filter(double minDiameter, double maxDiameter, double maxLength, String minClass, String maxClass,
			String manufacturer, String designationContains, String type, boolean availableOnly) {
	}

	public static List<ThrustCurveMotor> search(Filter f) {
		ThrustCurveMotorSetDatabase db = OrRuntime.motors();
		List<ThrustCurveMotor> out = new ArrayList<>();
		double minI = f.minClass() == null ? 0 : classMaxImpulse(f.minClass()) / 2;
		double maxI = f.maxClass() == null ? Double.MAX_VALUE : classMaxImpulse(f.maxClass()) * 1.0001;
		for (ThrustCurveMotorSet set : db.getMotorSets()) {
			for (ThrustCurveMotor m : set.getMotors()) {
				double d = m.getDiameter();
				double i = m.getTotalImpulseEstimate();
				if (!Double.isNaN(f.minDiameter()) && d < f.minDiameter() - 0.0005) {
					continue;
				}
				if (!Double.isNaN(f.maxDiameter()) && d > f.maxDiameter() + 0.0005) {
					continue;
				}
				if (!Double.isNaN(f.maxLength()) && m.getLength() > f.maxLength() + 0.0005) {
					continue;
				}
				if (i <= minI || i > maxI) {
					continue;
				}
				if (f.manufacturer() != null && !m.getManufacturer().matches(f.manufacturer())
						&& !m.getManufacturer().getDisplayName().toLowerCase(Locale.ROOT).contains(f.manufacturer().toLowerCase(Locale.ROOT))) {
					continue;
				}
				if (f.designationContains() != null && !m.getDesignation().toLowerCase(Locale.ROOT)
						.contains(f.designationContains().toLowerCase(Locale.ROOT))) {
					continue;
				}
				if (f.type() != null && !m.getMotorType().name().equalsIgnoreCase(f.type())) {
					continue;
				}
				if (f.availableOnly() && !m.isAvailable()) {
					continue;
				}
				out.add(m);
			}
		}
		out.sort((a, b) -> Double.compare(a.getTotalImpulseEstimate(), b.getTotalImpulseEstimate()));
		return out;
	}

	/**
	 * Finds one motor by digest, "Manufacturer Designation", or designation. Case and spaces are ignored.
	 */
	public static ThrustCurveMotor find(String ref, String manufacturer) {
		String r = ref.trim();
		String exact = r.replace(" ", "").toLowerCase(Locale.ROOT);
		String simple = ThrustCurveMotor.Builder.simplifyDesignation(r).toLowerCase(Locale.ROOT);
		List<ThrustCurveMotor> exactMatches = new ArrayList<>();
		List<ThrustCurveMotor> looseMatches = new ArrayList<>();
		for (ThrustCurveMotorSet set : OrRuntime.motors().getMotorSets()) {
			for (ThrustCurveMotor m : set.getMotors()) {
				if (m.getDigest() != null && m.getDigest().equalsIgnoreCase(r)) {
					return m;
				}
				boolean mfgMatch = manufacturer == null || m.getManufacturer().matches(manufacturer)
						|| m.getManufacturer().getDisplayName().toLowerCase(Locale.ROOT).contains(manufacturer.toLowerCase(Locale.ROOT));
				String full = (m.getManufacturer().getSimpleName() + m.getDesignation()).replace(" ", "").toLowerCase(Locale.ROOT);
				if (m.getDesignation().replace(" ", "").equalsIgnoreCase(exact) || full.equals(exact)) {
					if (mfgMatch || full.equals(exact)) {
						exactMatches.add(m);
					}
					continue;
				}
				String des = ThrustCurveMotor.Builder.simplifyDesignation(m.getDesignation()).toLowerCase(Locale.ROOT);
				if (mfgMatch && (des.equals(simple) || m.getCommonName().equalsIgnoreCase(r))) {
					looseMatches.add(m);
				}
			}
		}
		List<ThrustCurveMotor> matches = exactMatches.isEmpty() ? looseMatches : exactMatches;
		// "CTI L1395", "AeroTech K828FJ": the maker (or its usual abbreviation) in front of the designation.
		int space = r.indexOf(' ');
		if (matches.isEmpty() && manufacturer == null && space > 0) {
			try {
				return find(r.substring(space + 1), r.substring(0, space));
			} catch (ToolException e) {
				if (e.getMessage().contains("ambiguous")) {
					throw e;
				}
			}
		}
		if (matches.isEmpty()) {
			throw new ToolException("No motor matches '" + ref + "'" + (manufacturer == null ? "" : " from " + manufacturer)
					+ ". Use search_motors, or import_motor_file / create_custom_motor for engines not in the database.");
		}
		String first = matches.get(0).getManufacturer().getSimpleName();
		String firstDes = matches.get(0).getDesignation();
		boolean same = matches.stream().allMatch(m -> m.getManufacturer().getSimpleName().equals(first)
				&& m.getDesignation().equalsIgnoreCase(firstDes));
		if (!same) {
			StringBuilder sb = new StringBuilder();
			for (ThrustCurveMotor m : matches) {
				sb.append("\n  ").append(m.getManufacturer().getSimpleName()).append(' ').append(m.getDesignation())
						.append(" digest=").append(m.getDigest());
			}
			throw new ToolException("'" + ref + "' is ambiguous; pass the exact designation, manufacturer or a digest:" + sb);
		}
		// The same motor is often listed from several thrust-curve sources; prefer one in production.
		for (ThrustCurveMotor m : matches) {
			if (m.isAvailable()) {
				return m;
			}
		}
		return matches.get(0);
	}

	/**
	 * Downloads a motor's thrust curve from ThrustCurve.org (search by maker and designation, then the RASP file), writes
	 * it to {@code dir} and loads it. For motors newer than OpenRocket's built-in database.
	 */
	public static ThrustCurveMotor fromThrustCurve(String maker, String designation, Path dir) throws IOException {
		JsonObject q = new JsonObject();
		q.addProperty("designation", designation);
		if (maker != null) {
			q.addProperty("manufacturer", maker);
		}
		q.addProperty("maxResults", 5);
		JsonArray found = JsonParser.parseString(Web.postJson("https://www.thrustcurve.org/api/v1/search.json", q.toString()))
				.getAsJsonObject().getAsJsonArray("results");
		if (found == null || found.isEmpty()) {
			throw new ToolException("ThrustCurve.org has no motor '" + designation + "'" + (maker == null ? "" : " from " + maker) + ".");
		}
		JsonObject hit = found.get(0).getAsJsonObject();
		JsonObject dl = new JsonObject();
		JsonArray ids = new JsonArray();
		ids.add(hit.get("motorId").getAsString());
		dl.add("motorIds", ids);
		dl.addProperty("format", "RASP");
		dl.addProperty("data", "file");
		JsonArray files = JsonParser.parseString(Web.postJson("https://www.thrustcurve.org/api/v1/download.json", dl.toString()))
				.getAsJsonObject().getAsJsonArray("results");
		if (files == null || files.isEmpty() || !files.get(0).getAsJsonObject().has("data")) {
			throw new ToolException("ThrustCurve.org has no downloadable thrust curve for " + designation + ".");
		}
		byte[] eng = Base64.getDecoder().decode(files.get(0).getAsJsonObject().get("data").getAsString());
		Files.createDirectories(dir);
		Path file = dir.resolve(designation.replaceAll("[^A-Za-z0-9._-]", "_") + ".eng");
		Files.write(file, eng);
		List<ThrustCurveMotor> loaded = importFile(file);
		for (ThrustCurveMotor m : loaded) {
			if (ThrustCurveMotor.Builder.simplifyDesignation(m.getDesignation()).equalsIgnoreCase(
					ThrustCurveMotor.Builder.simplifyDesignation(designation))) {
				return m;
			}
		}
		return loaded.get(0);
	}

	/** Loads .eng / .rse / .zip motor files into the session's motor database. */
	public static List<ThrustCurveMotor> importFile(Path path) throws IOException {
		Path p = path.toAbsolutePath().normalize();
		if (!Files.exists(p)) {
			throw new ToolException("File not found: " + p);
		}
		try (InputStream in = Files.newInputStream(p)) {
			return load(in, p.getFileName().toString());
		}
	}

	private static List<ThrustCurveMotor> load(InputStream in, String filename) throws IOException {
		return load(in, filename, true);
	}

	/** {@code keep}: add the motors to the database (false for one that is only flown once, to try it). */
	private static List<ThrustCurveMotor> load(InputStream in, String filename, boolean keep) throws IOException {
		List<ThrustCurveMotor> out = new ArrayList<>();
		for (ThrustCurveMotor.Builder b : new GeneralMotorLoader().load(in, filename)) {
			ThrustCurveMotor m = b.build();
			if (keep) {
				OrRuntime.motors().addMotor(m);
			}
			out.add(m);
		}
		if (out.isEmpty()) {
			throw new ToolException("No motors found in " + filename);
		}
		return out;
	}

	/** Definition of a custom engine (liquid, hybrid, research solid). All values SI. */
	public record CustomMotor(String manufacturer, String designation, String type, double diameter, double length,
			double totalMass, double propellantMass, double[] time, double[] thrust, double dryCgFromTop,
			double propellantCgFromTop, double[] delays, String comment) {
	}

	/**
	 * Writes the custom motor as a RockSim .rse file (which, unlike .eng, stores mass and CG for each point, so
	 * the CG shift of propellant tanks is modeled), loads it and returns it with the file path.
	 */
	public static ThrustCurveMotor createCustom(CustomMotor c, Path rseFile) throws IOException {
		return createCustom(c, rseFile, null, null);
	}

	/**
	 * As above, with the propellant left (kg) and the whole motor's CG from its top (m) at each point of the curve
	 * when they are known (an engine model, a weighed test) instead of assumed: without them the propellant is taken
	 * to go in proportion to the impulse delivered, at one place. The curve must then start at time zero.
	 */
	public static ThrustCurveMotor createCustom(CustomMotor c, Path rseFile, double[] propellantLeft, double[] cgFromTop)
			throws IOException {
		return createCustom(c, rseFile, propellantLeft, cgFromTop, true);
	}

	/** As above; {@code keep} false builds the motor without adding it to the database or writing a file. */
	public static ThrustCurveMotor createCustom(CustomMotor c, Path rseFile, double[] propellantLeft, double[] cgFromTop,
			boolean keep) throws IOException {
		if (propellantLeft != null && (propellantLeft.length != c.time().length || c.time()[0] != 0
				|| (cgFromTop != null && cgFromTop.length != c.time().length))) {
			throw new IllegalArgumentException("propellantLeft and cgFromTop go point for point with a curve that starts at t = 0");
		}
		if (c.time().length != c.thrust().length || c.time().length < 2) {
			throw new ToolException("thrustCurve needs at least two [time, thrust] points.");
		}
		if (c.propellantMass() <= 0 || c.propellantMass() >= c.totalMass()) {
			throw new ToolException("propellantMass must be positive and less than totalMass.");
		}
		if (c.designation() == null || c.designation().isBlank() || c.manufacturer() == null || c.manufacturer().isBlank()) {
			throw new ToolException("Give the motor a designation and a manufacturer (team name is fine).");
		}
		if (!(c.diameter() > 0) || !(c.length() > 0)) {
			throw new ToolException("diameter and length must be greater than zero.");
		}
		for (int i = 0; i < c.time().length; i++) {
			double ti = c.time()[i], fi = c.thrust()[i];
			if (!Double.isFinite(ti) || !Double.isFinite(fi) || ti < 0 || fi < 0) {
				throw new ToolException("thrustCurve point " + (i + 1) + " [" + ti + ", " + fi + "]: times and thrusts must be "
						+ "finite and not negative.");
			}
			if (i > 0 && ti <= c.time()[i - 1]) {
				throw new ToolException("thrustCurve times must increase (point " + (i + 1) + " at " + ti + " s follows "
						+ c.time()[i - 1] + " s).");
			}
			if (fi > ThrustCurveMotor.MAX_THRUST) {
				throw new ToolException("thrustCurve point " + (i + 1) + ": " + Units.fmt(fi, Dim.FORCE) + " is beyond the "
						+ Units.fmt(ThrustCurveMotor.MAX_THRUST, Dim.FORCE) + " OpenRocket can model; check the unit (N).");
			}
		}
		for (double cg : new double[] { c.dryCgFromTop(), c.propellantCgFromTop() }) {
			if (!Double.isNaN(cg) && (cg < 0 || cg > c.length())) {
				throw new ToolException("dryCgFromTop and propellantCgFromTop are measured from the top of the motor and must "
						+ "lie within its " + Units.fmt(c.length(), Dim.LENGTH) + " length (got " + Units.fmt(cg, Dim.LENGTH) + ").");
			}
		}
		if (c.delays() != null) {
			for (double d : c.delays()) {
				if (!(d >= 0) || d > 1000) {
					throw new ToolException("Ejection delays are seconds from burnout, 0 or more (got " + d + ").");
				}
			}
		}
		int n = c.time().length;
		double[] t = new double[n + (c.time()[0] > 0 ? 1 : 0)];
		double[] f = new double[t.length];
		int o = 0;
		if (c.time()[0] > 0) {
			t[0] = 0;
			f[0] = 0;
			o = 1;
		}
		for (int i = 0; i < n; i++) {
			t[i + o] = c.time()[i];
			f[i + o] = c.thrust()[i];
		}
		// Cumulative impulse (trapezoidal); propellant is consumed in proportion to impulse delivered.
		double[] impulse = new double[t.length];
		for (int i = 1; i < t.length; i++) {
			impulse[i] = impulse[i - 1] + 0.5 * (f[i] + f[i - 1]) * (t[i] - t[i - 1]);
		}
		double total = impulse[t.length - 1];
		if (total <= 0) {
			throw new ToolException("Thrust curve has no impulse.");
		}
		double dry = c.totalMass() - c.propellantMass();
		double xDry = Double.isNaN(c.dryCgFromTop()) ? c.length() / 2 : c.dryCgFromTop();
		double xProp = Double.isNaN(c.propellantCgFromTop()) ? c.length() / 2 : c.propellantCgFromTop();
		String type = switch (c.type() == null ? "hybrid" : c.type().toLowerCase(Locale.ROOT)) {
			case "single", "single-use", "solid" -> "single-use";
			case "reload", "reloadable" -> "reloadable";
			default -> "hybrid"; // OpenRocket has no liquid type; hybrid is the closest (no ejection charge)
		};
		StringBuilder delays = new StringBuilder();
		if (c.delays() == null || c.delays().length == 0) {
			delays.append("P");
		} else {
			for (double d : c.delays()) {
				// OpenRocket's RockSim reader splits the list on commas ("10-14", the .eng style, reads as no delays).
				delays.append(delays.length() == 0 ? "" : ",").append(Units.num(d));
			}
		}
		StringBuilder xml = new StringBuilder();
		xml.append("<engine-database>\n <engine-list>\n");
		xml.append(String.format(Locale.ROOT,
				"  <engine mfg=\"%s\" code=\"%s\" Type=\"%s\" dia=\"%.3f\" len=\"%.3f\" initWt=\"%.3f\" propWt=\"%.3f\""
						+ " delays=\"%s\" auto-calc-mass=\"0\" auto-calc-cg=\"0\" avgThrust=\"%.3f\" peakThrust=\"%.3f\""
						+ " burn-time=\"%.4f\" tot-impulse=\"%.3f\">\n",
				Xml.esc(c.manufacturer()), Xml.esc(c.designation()), type, c.diameter() * 1000, c.length() * 1000,
				c.totalMass() * 1000, c.propellantMass() * 1000, delays, total / t[t.length - 1], max(f),
				t[t.length - 1], total));
		xml.append("   <comments>").append(Xml.esc(c.comment() == null ? "Created by openrocket-mcp" : c.comment()))
				.append("</comments>\n   <data>\n");
		for (int i = 0; i < t.length; i++) {
			double prop = propellantLeft != null ? propellantLeft[i] : c.propellantMass() * (1 - impulse[i] / total);
			double mass = dry + prop;
			double cg = cgFromTop != null ? cgFromTop[i] : (dry * xDry + prop * xProp) / mass;
			xml.append(String.format(Locale.ROOT, "    <eng-data t=\"%.5f\" f=\"%.4f\" m=\"%.4f\" cg=\"%.4f\"/>\n",
					t[i], f[i], mass * 1000, cg * 1000));
		}
		xml.append("   </data>\n  </engine>\n </engine-list>\n</engine-database>\n");
		byte[] bytes = xml.toString().getBytes(StandardCharsets.UTF_8);
		if (rseFile != null && keep) {
			Path p = rseFile.toAbsolutePath().normalize();
			if (p.getParent() != null) {
				Files.createDirectories(p.getParent());
			}
			Files.write(p, bytes);
		}
		return load(new ByteArrayInputStream(bytes), c.designation() + ".rse", keep).get(0);
	}

	private static double max(double[] v) {
		double m = 0;
		for (double d : v) {
			m = Math.max(m, d);
		}
		return m;
	}
}
