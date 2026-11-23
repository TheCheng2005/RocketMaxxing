package io.github.openrocketmcp.or;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationOptions;

import io.github.openrocketmcp.mcp.CallContext;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Launch planning from an ensemble weather forecast: the design flown many times, each flight in one ensemble member's
 * winds (the forecast's own spread of possible weather) with the launch angle dispersed, and where it lands, as the
 * areas holding 50, 95 and 99% of the landings on the map. The ensemble comes from Open-Meteo's ensemble API (free, no
 * key): ECMWF's IFS ensemble (51 forecasts, winds at every height, 15 days) or NOAA's GEFS (31 forecasts, 10 days; its
 * members carry ground wind only, so winds aloft are the deterministic forecast's, moved by each member's difference at
 * the ground).
 */
public final class LaunchPlan {
	private LaunchPlan() {
	}

	public static final int[] LEVELS = { 1000, 925, 850, 700, 600, 500, 400, 300, 250, 200, 150, 100 };
	static final double EARTH_RADIUS = 6371000;

	/** The ensemble models offered: Open-Meteo name, what people call it, forecast days. */
	public enum Model {
		ECMWF("ecmwf_ifs025", "ECMWF IFS ensemble (51 forecasts)", 15, true), GFS("gfs025", "NOAA GEFS ensemble (31 forecasts)", 10, false);

		public final String id, label;
		public final int days;
		/** Whether each member has its own winds aloft. */
		public final boolean aloft;

		Model(String id, String label, int days, boolean aloft) {
			this.id = id;
			this.label = label;
			this.days = days;
			this.aloft = aloft;
		}

		public static Model of(String s) {
			if (s == null || s.isBlank()) {
				return ECMWF;
			}
			String k = s.trim().toLowerCase(Locale.ROOT);
			return k.startsWith("gfs") || k.startsWith("gefs") || k.startsWith("noaa") ? GFS : ECMWF;
		}
	}

	/** One forecast of the ensemble at the launch hour: site conditions and the wind profile (AGL). */
	public record Member(String name, double temperature, double pressure, double gust, List<Winds.Level> levels) {
	}

	/** The ensemble at the launch hour. */
	public record Ensemble(Model model, String time, String timezone, double latitude, double longitude, double elevation,
			List<Member> members, String note) {
	}

	// ------------------------------------------------------------------------------------------------ fetching

	public static String baseUrl() {
		String p = System.getProperty("openrocketmcp.ensembleUrl");
		if (p != null && !p.isBlank()) {
			return p;
		}
		String e = System.getenv("OPENROCKET_MCP_ENSEMBLE_URL");
		return e != null && !e.isBlank() ? e : "https://ensemble-api.open-meteo.com/v1/ensemble";
	}

	/** The request: the launch day only (site time), the variables the model has. */
	public static String url(Model m, double lat, double lon, String date) {
		StringBuilder v = new StringBuilder("wind_speed_10m,wind_direction_10m,wind_gusts_10m,temperature_2m,surface_pressure");
		if (m.aloft) {
			v.append(",wind_speed_100m,wind_direction_100m");
			for (int p : LEVELS) {
				v.append(",wind_speed_").append(p).append("hPa,wind_direction_").append(p).append("hPa,geopotential_height_").append(p).append("hPa");
			}
		}
		String when = date == null || date.isBlank() ? "&forecast_days=" + m.days : "&start_date=" + date + "&end_date=" + date;
		return String.format(Locale.ROOT, "%s?latitude=%.5f&longitude=%.5f&models=%s&hourly=%s&wind_speed_unit=ms&timezone=auto%s",
				baseUrl(), lat, lon, m.id, v, when);
	}

	public static JsonObject fetch(String url) {
		try {
			HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
			HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
					.header("User-Agent", "RocketMaxxing").GET().build(), HttpResponse.BodyHandlers.ofString());
			JsonObject json = null;
			try {
				json = JsonParser.parseString(r.body()).getAsJsonObject();
			} catch (RuntimeException e) {
				// not JSON: the status says what went wrong
			}
			if (r.statusCode() != 200) {
				String why = json != null && json.has("reason") ? json.get("reason").getAsString()
						: r.body().substring(0, Math.min(300, r.body().length()));
				throw ToolException.system("The ensemble forecast service said: " + why + (why.toLowerCase(Locale.ROOT).contains("limit")
						? " (Open-Meteo's free daily limit is shared by everyone on the same internet connection; try again later)" : ""));
			}
			return json;
		} catch (IOException e) {
			throw ToolException.system("Could not reach the ensemble forecast service (" + e.getMessage() + "). Without internet access, "
					+ "save the forecast from " + url.substring(0, Math.min(90, url.length())) + "... and give it as ensembleJson.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ToolException("Interrupted.");
		}
	}

	// ------------------------------------------------------------------------------------------------ reading

	/**
	 * The members at the hour nearest {@code time} (site time "yyyy-MM-ddTHH:mm"). For a model whose members have no
	 * winds aloft, {@code aloft} (the deterministic forecast at that hour) gives the profile, moved by each member's
	 * ground wind minus the ensemble's mean ground wind.
	 */
	public static Ensemble parse(JsonObject json, Model m, String time, Weather.Forecast aloft) {
		if (json.has("error") && json.get("error").getAsBoolean()) {
			throw new ToolException("Ensemble forecast error: " + (json.has("reason") ? json.get("reason").getAsString() : json));
		}
		JsonObject h = json.getAsJsonObject("hourly");
		if (h == null || !h.has("time")) {
			throw new ToolException("Not an Open-Meteo ensemble forecast (no hourly.time).");
		}
		JsonArray times = h.getAsJsonArray("time");
		int offset = json.has("utc_offset_seconds") ? json.get("utc_offset_seconds").getAsInt() : 0;
		String tz = json.has("timezone") ? json.get("timezone").getAsString() : "UTC";
		LocalDateTime want = time == null || time.isBlank() ? LocalDateTime.now(ZoneOffset.ofTotalSeconds(offset))
				: LocalDateTime.parse(time.length() == 13 ? time + ":00" : time.length() == 10 ? time + "T12:00" : time);
		int best = -1;
		long bestDiff = Long.MAX_VALUE;
		for (int i = 0; i < times.size(); i++) {
			long d = Math.abs(Duration.between(want, LocalDateTime.parse(times.get(i).getAsString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)).toMinutes());
			if (d < bestDiff) {
				bestDiff = d;
				best = i;
			}
		}
		if (best < 0 || bestDiff > 90) {
			throw new ToolException("No forecast hour near " + (time == null ? "now" : time) + " (the forecast covers "
					+ (times.isEmpty() ? "nothing" : times.get(0).getAsString() + " to " + times.get(times.size() - 1).getAsString())
					+ ", site time " + tz + "; " + m.label + " goes " + m.days + " days ahead).");
		}
		double elev = json.has("elevation") ? json.get("elevation").getAsDouble() : 0;
		// Members: the control (no suffix) and _member01.. as the response has them.
		List<String> suffixes = new ArrayList<>();
		suffixes.add("");
		for (String k : h.keySet()) {
			if (k.startsWith("wind_speed_10m_member")) {
				suffixes.add(k.substring("wind_speed_10m".length()));
			}
		}
		suffixes.sort((a, b) -> a.compareTo(b));
		double meanU = 0, meanV = 0;
		int nGround = 0;
		for (String sfx : suffixes) {
			double s = val(h, "wind_speed_10m" + sfx, best), d = val(h, "wind_direction_10m" + sfx, best);
			if (!Double.isNaN(s) && !Double.isNaN(d)) {
				meanU += -s * Math.sin(Math.toRadians(d));
				meanV += -s * Math.cos(Math.toRadians(d));
				nGround++;
			}
		}
		meanU /= Math.max(1, nGround);
		meanV /= Math.max(1, nGround);
		List<Member> members = new ArrayList<>();
		for (String sfx : suffixes) {
			double s10 = val(h, "wind_speed_10m" + sfx, best), d10 = val(h, "wind_direction_10m" + sfx, best);
			if (Double.isNaN(s10) || Double.isNaN(d10)) {
				continue;
			}
			double gust = val(h, "wind_gusts_10m" + sfx, best);
			double sd = Weather.sd(s10, gust);
			List<Winds.Level> ls = new ArrayList<>();
			ls.add(new Winds.Level(0, s10, Math.toRadians(d10), sd));
			ls.add(new Winds.Level(10, s10, Math.toRadians(d10), sd));
			if (m.aloft) {
				double s100 = val(h, "wind_speed_100m" + sfx, best), d100 = val(h, "wind_direction_100m" + sfx, best);
				if (!Double.isNaN(s100) && !Double.isNaN(d100)) {
					ls.add(new Winds.Level(100, s100, Math.toRadians(d100), 0.1 * s100));
				}
				double top = ls.get(ls.size() - 1).altitude();
				for (int p : LEVELS) {
					double s = val(h, "wind_speed_" + p + "hPa" + sfx, best), d = val(h, "wind_direction_" + p + "hPa" + sfx, best);
					double z = val(h, "geopotential_height_" + p + "hPa" + sfx, best);
					if (Double.isNaN(s) || Double.isNaN(d) || Double.isNaN(z) || z - elev <= top + 20) {
						continue;
					}
					ls.add(new Winds.Level(z - elev, s, Math.toRadians(d), 0.1 * s));
					top = z - elev;
				}
			} else if (aloft != null) {
				// The deterministic profile, moved by this member's ground wind minus the ensemble mean (as vectors).
				double du = -s10 * Math.sin(Math.toRadians(d10)) - meanU, dv = -s10 * Math.cos(Math.toRadians(d10)) - meanV;
				for (Winds.Level l : aloft.levels()) {
					if (l.altitude() <= 10) {
						continue;
					}
					double u = -l.speed() * Math.sin(l.direction()) + du, v = -l.speed() * Math.cos(l.direction()) + dv;
					double sp = Math.hypot(u, v);
					ls.add(new Winds.Level(l.altitude(), sp, Winds.norm(Math.atan2(-u, -v)), l.sd()));
				}
			}
			ls.sort((a, b) -> Double.compare(a.altitude(), b.altitude()));
			double t2 = val(h, "temperature_2m" + sfx, best), ps = val(h, "surface_pressure" + sfx, best);
			members.add(new Member(sfx.isEmpty() ? "control" : sfx.replace("_member", "member "), Double.isNaN(t2) ? Double.NaN : t2 + 273.15,
					Double.isNaN(ps) ? Double.NaN : ps * 100, gust, ls));
		}
		if (members.isEmpty()) {
			throw new ToolException("The ensemble forecast has no ground wind for " + times.get(best).getAsString() + ".");
		}
		String note = m.aloft ? "Each forecast has its own winds up to " + Units.fmt(members.get(0).levels.get(members.get(0).levels.size() - 1).altitude(), Dim.DISTANCE)
				+ " above the ground."
				: aloft != null ? "Each forecast's winds aloft are the deterministic forecast's, moved by that forecast's difference from the ensemble at the ground "
						+ "(the GEFS members carry ground wind only)."
						: "The GEFS members carry ground wind only, and no deterministic forecast was given: winds aloft are left out (ground wind at every height).";
		return new Ensemble(m, times.get(best).getAsString(), tz, num(json, "latitude"), num(json, "longitude"), elev, members, note);
	}

	static double val(JsonObject hourly, String key, int i) {
		JsonElement a = hourly.get(key);
		if (a == null || !a.isJsonArray() || i >= a.getAsJsonArray().size() || a.getAsJsonArray().get(i).isJsonNull()) {
			return Double.NaN;
		}
		return a.getAsJsonArray().get(i).getAsDouble();
	}

	static double num(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : Double.NaN;
	}

	// ------------------------------------------------------------------------------------------------ flying

	/** Dispersion of the launch: angle sd (rad) and rod direction sd (rad), runs, seed, and the area to stay inside. */
	public record Settings(int runs, double launchAngleSd, double launchDirectionSd, long seed, double radius) {
	}

	/** Each flight in one member's weather; the control flown as the forecast as it stands. Map data in lat/lon. */
	public static Map<String, Object> plan(Simulation base, OpenRocketDocument doc, Ensemble e, Settings s, double lat, double lon) {
		SimulationOptions bo = base.getOptions();
		Random rnd = new Random(s.seed());
		List<Simulation> sims = new ArrayList<>();
		List<Integer> memberOf = new ArrayList<>();
		// The forecast as it stands: the control member, no dispersion.
		sims.add(flight(base, doc, e, e.members().get(0), bo.getLaunchRodAngle(), bo.getLaunchRodDirection(), 1, lat, lon));
		memberOf.add(0);
		for (int i = 0; i < s.runs(); i++) {
			// Each forecast in turn; with fewer flights than forecasts, spread over all of them rather than the first few.
			int n = e.members().size();
			int k = s.runs() >= n ? i % n : (int) ((long) i * n / s.runs());
			double angle = Math.min(Math.toRadians(60), Math.abs(MonteCarlo.gauss(rnd, bo.getLaunchRodAngle(), s.launchAngleSd())));
			double dir = MonteCarlo.gauss(rnd, bo.getLaunchRodDirection(), s.launchDirectionSd());
			sims.add(flight(base, doc, e, e.members().get(k), angle, dir, rnd.nextInt(), lat, lon));
			memberOf.add(k);
		}
		CallContext.current().expect(sims.size(), "flights simulated");
		List<Variants.Run> runs = Variants.runAll(sims);

		Variants.Run asIs = runs.get(0);
		Map<String, List<double[]>> landings = new LinkedHashMap<>(); // branch -> {east, north}
		List<Double> apogees = new ArrayList<>(), flightTimes = new ArrayList<>(), topSpeeds = new ArrayList<>(), landingSpeeds = new ArrayList<>();
		int failed = 0;
		for (int i = 1; i < runs.size(); i++) {
			Variants.Run r = runs.get(i);
			if (!r.ok()) {
				failed++;
				continue;
			}
			FlightData d = r.sim().getSimulatedData();
			apogees.add(d.getMaxAltitude());
			topSpeeds.add(d.getMaxVelocity());
			FlightDataBranch b0 = d.getBranch(0);
			FlightEvent hit0 = b0.getFirstEvent(FlightEvent.Type.GROUND_HIT);
			if (hit0 != null) {
				flightTimes.add(hit0.getTime());
				landingSpeeds.add(Math.abs(Branch.of(b0).last(FlightDataType.TYPE_VELOCITY_Z)));
			}
			for (FlightDataBranch b : d.getBranches()) {
				if (b.getFirstEvent(FlightEvent.Type.GROUND_HIT) == null) {
					continue;
				}
				Branch br = Branch.of(b);
				landings.computeIfAbsent(b.getName(), k -> new ArrayList<>())
						.add(new double[] { br.last(FlightDataType.TYPE_POSITION_X), br.last(FlightDataType.TYPE_POSITION_Y) });
			}
		}
		if (landings.isEmpty()) {
			throw new ToolException("None of the " + s.runs() + " flights landed" + (asIs.ok() ? "" : ": " + asIs.error()) + ".");
		}

		Map<String, Object> out = new LinkedHashMap<>();
		Map<String, Object> site = new LinkedHashMap<>();
		site.put("latitude", lat);
		site.put("longitude", lon);
		site.put("elevation", e.elevation());
		out.put("site", site);
		Map<String, Object> fc = new LinkedHashMap<>();
		fc.put("model", e.model().label);
		fc.put("time", e.time());
		fc.put("timezone", e.timezone());
		fc.put("forecasts", e.members().size());
		fc.put("note", e.note());
		out.put("forecast", fc);
		out.put("runs", s.runs());
		out.put("failedRuns", failed);

		// Summary numbers
		Map<String, Object> sum = new LinkedHashMap<>();
		double[] ap = MonteCarlo.sorted(apogees);
		sum.put("apogee", Units.fmt(MonteCarlo.pct(ap, 50), Dim.DISTANCE));
		sum.put("apogeeRange", Units.fmt(MonteCarlo.pct(ap, 5), Dim.DISTANCE) + " to " + Units.fmt(MonteCarlo.pct(ap, 95), Dim.DISTANCE)
				+ " in 90% of flights");
		if (!flightTimes.isEmpty()) {
			double[] ft = MonteCarlo.sorted(flightTimes);
			sum.put("flightTime", Units.fmt(MonteCarlo.pct(ft, 50), Dim.TIME) + " (" + Units.num(MonteCarlo.pct(ft, 5)) + " to "
					+ Units.fmt(MonteCarlo.pct(ft, 95), Dim.TIME) + ")");
			sum.put("landingSpeed", Units.fmt(MonteCarlo.pct(MonteCarlo.sorted(landingSpeeds), 50), Dim.VELOCITY));
		}
		sum.put("topSpeed", Units.fmt(MonteCarlo.pct(MonteCarlo.sorted(topSpeeds), 50), Dim.VELOCITY));
		out.put("summary", sum);

		// Where it lands, per stage
		List<Map<String, Object>> areas = new ArrayList<>();
		double farthest = 0;
		for (Map.Entry<String, List<double[]>> en : landings.entrySet()) {
			List<double[]> pts = en.getValue();
			Map<String, Object> a = new LinkedHashMap<>();
			a.put("branch", en.getKey());
			a.put("landings", pts.size());
			double[] c = mean(pts);
			double[][] cov = cov(pts, c);
			double[] md = new double[pts.size()];
			for (int i = 0; i < md.length; i++) {
				md[i] = mahalanobis(pts.get(i), c, cov);
				farthest = Math.max(farthest, Math.hypot(pts.get(i)[0], pts.get(i)[1]));
			}
			Arrays.sort(md);
			List<Map<String, Object>> rings = new ArrayList<>();
			for (int p : new int[] { 50, 95, 99 }) {
				double k = MonteCarlo.pct(md, p);
				double[] ax = axes(cov, k); // semi-major, semi-minor, angle of the major axis (rad from east, CCW)
				Map<String, Object> ring = new LinkedHashMap<>();
				ring.put("percent", p);
				ring.put("size", Units.fmt(2 * ax[0], Dim.DISTANCE) + " by " + Units.fmt(2 * ax[1], Dim.DISTANCE));
				ring.put("semiMajor", ax[0]);
				ring.put("semiMinor", ax[1]);
				ring.put("polygon", polygon(c, ax, lat, lon));
				rings.add(ring);
			}
			a.put("centre", Units.fmt(Math.hypot(c[0], c[1]), Dim.DISTANCE) + " " + compass(Math.atan2(c[0], c[1])) + " of the pad");
			a.put("centreLatLon", latLon(c[0], c[1], lat, lon));
			a.put("areas", rings);
			List<double[]> ll = new ArrayList<>();
			for (double[] p : pts) {
				ll.add(latLon(p[0], p[1], lat, lon));
			}
			a.put("points", ll);
			if (s.radius() > 0) {
				long outside = pts.stream().filter(p -> Math.hypot(p[0], p[1]) > s.radius()).count();
				a.put("beyondRadius", outside + " of " + pts.size() + " land more than " + Units.fmt(s.radius(), Dim.DISTANCE) + " from the pad");
			}
			areas.add(a);
		}
		sum.put("farthestLanding", Units.fmt(farthest, Dim.DISTANCE) + " from the pad");
		Map<String, Object> first = areas.get(0);
		@SuppressWarnings("unchecked")
		Map<String, Object> r95 = ((List<Map<String, Object>>) first.get("areas")).get(1);
		out.put("verdict", "95% of " + first.get("landings") + " flights land inside an area " + r95.get("size") + ", centred "
				+ first.get("centre") + (areas.size() > 1 ? " (" + first.get("branch") + ")" : "") + ".");
		out.put("landing", areas);

		// The forecast as it stands: its trajectory and landing.
		if (asIs.ok()) {
			FlightData d = asIs.sim().getSimulatedData();
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("apogee", Units.fmt(d.getMaxAltitude(), Dim.DISTANCE));
			List<Map<String, Object>> tracks = new ArrayList<>();
			for (FlightDataBranch b : d.getBranches()) {
				Branch br = Branch.of(b);
				double[] x = br.col(FlightDataType.TYPE_POSITION_X), y = br.col(FlightDataType.TYPE_POSITION_Y), z = br.col(FlightDataType.TYPE_ALTITUDE);
				List<double[]> path = new ArrayList<>();
				int step = Math.max(1, x.length / 300);
				int top = 0;
				for (int i = 0; i < x.length; i++) {
					if (!Double.isNaN(z[i]) && z[i] > z[top]) {
						top = i;
					}
				}
				for (int i = 0; i < x.length; i += step) {
					if (!Double.isNaN(x[i]) && !Double.isNaN(y[i])) {
						double[] p = latLon(x[i], y[i], lat, lon);
						path.add(new double[] { p[0], p[1], z[i] });
					}
				}
				Map<String, Object> tr = new LinkedHashMap<>();
				tr.put("branch", b.getName());
				tr.put("path", path);
				double[] apx = latLon(x[top], y[top], lat, lon);
				tr.put("apogee", new double[] { apx[0], apx[1], z[top] });
				if (b.getFirstEvent(FlightEvent.Type.GROUND_HIT) != null) {
					double lx = br.last(FlightDataType.TYPE_POSITION_X), ly = br.last(FlightDataType.TYPE_POSITION_Y);
					tr.put("landing", latLon(lx, ly, lat, lon));
					tr.put("landingFromPad", Units.fmt(Math.hypot(lx, ly), Dim.DISTANCE) + " " + compass(Math.atan2(lx, ly)));
				}
				tracks.add(tr);
			}
			f.put("tracks", tracks);
			out.put("forecastAsIs", f);
			sum.put("landingAsIs", tracks.get(0).getOrDefault("landingFromPad", "n/a"));
		}

		// Winds by height: the ensemble's median and range, from the control's direction.
		out.put("winds", windTable(e));
		out.put("notes", List.of(
				"Each flight flies one forecast of the ensemble (in turn), with the launch angle dispersed by "
						+ Units.fmt(s.launchAngleSd(), Dim.ANGLE) + " and the rod direction by " + Units.fmt(s.launchDirectionSd(), Dim.ANGLE)
						+ " (1 sd); turbulence from each forecast's gusts.",
				"Landing areas are ellipses holding 50, 95 and 99% of the landings (by their spread about the mean landing point).",
				"The forecast as it stands is the ensemble's control forecast, flown without dispersion.",
				"Forecast: " + e.model().label + " via Open-Meteo (open-meteo.com, CC BY 4.0). " + e.note()));
		return out;
	}

	/** The plan without what only a map needs (landing points, outlines, trajectories): the numbers stay. */
	@SuppressWarnings("unchecked")
	public static void withoutMapData(Map<String, Object> plan) {
		for (Map<String, Object> a : (List<Map<String, Object>>) plan.getOrDefault("landing", List.of())) {
			a.remove("points");
			a.remove("centreLatLon");
			for (Map<String, Object> r : (List<Map<String, Object>>) a.getOrDefault("areas", List.of())) {
				r.remove("polygon");
			}
		}
		Object f = plan.get("forecastAsIs");
		if (f instanceof Map<?, ?> m) {
			for (Map<String, Object> t : (List<Map<String, Object>>) ((Map<String, Object>) m).getOrDefault("tracks", List.of())) {
				t.remove("path");
				t.remove("apogee");
				t.remove("landing");
			}
		}
	}

	static Simulation flight(Simulation base, OpenRocketDocument doc, Ensemble e, Member m, double angle, double dir, int seed, double lat,
			double lon) {
		Simulation v = Variants.of(base, doc, null, null);
		SimulationOptions o = v.getOptions();
		Weather.Forecast f = new Weather.Forecast(lat, lon, e.elevation(), e.time(), e.timezone(), m.levels().get(0).speed(),
				m.levels().get(0).direction(), m.gust(), m.temperature(), m.pressure(), m.levels());
		Weather.apply(o, f, Double.MAX_VALUE, lat, lon);
		o.setLaunchRodAngle(angle);
		o.setLaunchRodDirection(((dir % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI));
		Variants.seed(o, seed);
		o.setTimeStep(Math.max(o.getTimeStep(), 0.1));
		return v;
	}

	static List<Map<String, Object>> windTable(Ensemble e) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Winds.Level l : e.members().get(0).levels()) {
			if (l.altitude() <= 0) {
				continue;
			}
			double[] sp = new double[e.members().size()];
			for (int i = 0; i < sp.length; i++) {
				sp[i] = speedAt(e.members().get(i).levels(), l.altitude());
			}
			Arrays.sort(sp);
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("height", Units.fmt(l.altitude(), Dim.DISTANCE));
			r.put("speed", Units.fmt(MonteCarlo.pct(sp, 50), Dim.VELOCITY));
			r.put("ensembleRange", Units.fmt(sp[0], Dim.VELOCITY) + " to " + Units.fmt(sp[sp.length - 1], Dim.VELOCITY));
			r.put("from", compass(l.direction()) + " (" + Math.round(Math.toDegrees(l.direction())) + "°)");
			rows.add(r);
		}
		return rows;
	}

	static double speedAt(List<Winds.Level> ls, double z) {
		if (z <= ls.get(0).altitude()) {
			return ls.get(0).speed();
		}
		for (int i = 1; i < ls.size(); i++) {
			if (z <= ls.get(i).altitude()) {
				Winds.Level a = ls.get(i - 1), b = ls.get(i);
				return a.speed() + (b.speed() - a.speed()) * (z - a.altitude()) / (b.altitude() - a.altitude());
			}
		}
		return ls.get(ls.size() - 1).speed();
	}

	// ------------------------------------------------------------------------------------------------ geometry

	static double[] mean(List<double[]> p) {
		double x = 0, y = 0;
		for (double[] v : p) {
			x += v[0];
			y += v[1];
		}
		return new double[] { x / p.size(), y / p.size() };
	}

	static double[][] cov(List<double[]> p, double[] c) {
		double xx = 0, yy = 0, xy = 0;
		for (double[] v : p) {
			double dx = v[0] - c[0], dy = v[1] - c[1];
			xx += dx * dx;
			yy += dy * dy;
			xy += dx * dy;
		}
		int n = Math.max(1, p.size() - 1);
		// A floor of 1 m so a spread-free set (one forecast, no dispersion) still has an area to draw.
		return new double[][] { { xx / n + 1, xy / n }, { xy / n, yy / n + 1 } };
	}

	static double mahalanobis(double[] p, double[] c, double[][] s) {
		double det = s[0][0] * s[1][1] - s[0][1] * s[1][0];
		double dx = p[0] - c[0], dy = p[1] - c[1];
		return Math.sqrt((s[1][1] * dx * dx - 2 * s[0][1] * dx * dy + s[0][0] * dy * dy) / det);
	}

	/** Semi-axes of the ellipse at Mahalanobis distance k, and the major axis angle (rad from east, counter-clockwise). */
	static double[] axes(double[][] s, double k) {
		double a = s[0][0], b = s[0][1], d = s[1][1];
		double tr = a + d, det = a * d - b * b, disc = Math.sqrt(Math.max(0, tr * tr / 4 - det));
		double l1 = tr / 2 + disc, l2 = Math.max(0, tr / 2 - disc);
		double ang = Math.abs(b) < 1e-12 ? (a >= d ? 0 : Math.PI / 2) : Math.atan2(l1 - a, b);
		return new double[] { k * Math.sqrt(l1), k * Math.sqrt(l2), ang };
	}

	static List<double[]> polygon(double[] c, double[] ax, double lat, double lon) {
		List<double[]> out = new ArrayList<>();
		for (int i = 0; i <= 72; i++) {
			double t = 2 * Math.PI * i / 72;
			double ex = ax[0] * Math.cos(t), ey = ax[1] * Math.sin(t);
			double x = c[0] + ex * Math.cos(ax[2]) - ey * Math.sin(ax[2]), y = c[1] + ex * Math.sin(ax[2]) + ey * Math.cos(ax[2]);
			out.add(latLon(x, y, lat, lon));
		}
		return out;
	}

	/** East and north (m) from the pad to latitude and longitude (degrees). */
	static double[] latLon(double east, double north, double lat, double lon) {
		double la = lat + Math.toDegrees(north / EARTH_RADIUS);
		double lo = lon + Math.toDegrees(east / (EARTH_RADIUS * Math.cos(Math.toRadians(lat))));
		return new double[] { Math.round(la * 1e7) / 1e7, Math.round(lo * 1e7) / 1e7 };
	}

	/** A bearing (rad clockwise from north) as a compass point. */
	static String compass(double bearing) {
		String[] p = { "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW" };
		int i = (int) Math.round(((Math.toDegrees(bearing) % 360) + 360) % 360 / 22.5) % 16;
		return p[i];
	}
}
