package io.github.openrocketmcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.LaunchPlan;
import io.github.openrocketmcp.or.RailButtons;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Weather;
import io.github.openrocketmcp.or.Winds;
import io.github.openrocketmcp.report.FlightCard;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** Launch day: weather forecast for the site, rail buttons and the flight card. */
public final class LaunchTools {
	private LaunchTools() {
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("weather_forecast", "Launch-site wind and weather forecast",
				"Get the forecast for the launch site from Open-Meteo (free, no key) by GPS coordinates: ground wind, gusts, "
						+ "temperature and pressure, and wind at altitude up to the jet stream (pressure levels). By default it is "
						+ "applied to the design's simulation (wind profile, site altitude, temperature, pressure) so every tool flies "
						+ "the forecast, and the flight is re-simulated. Ask the user for the site's latitude / longitude if the team "
						+ "standards do not have them. Without internet access, pass the Open-Meteo JSON as forecastJson, or enter "
						+ "winds by hand with wind_profile.",
				SimTools.simSelect(Schema.object())
						.num("latitude", "Launch site latitude, decimal degrees (default: launchSite.latitude).", false)
						.num("longitude", "Launch site longitude, decimal degrees (default: launchSite.longitude).", false)
						.str("time", "Launch time in the site's local time, e.g. \"2027-08-21T10:00\" (default: next hour).", false)
						.qty("top", "Highest wind level to use (default 12 km).", false)
						.bool("apply", "Apply to the simulation and re-simulate (default true; needs an open design).", false)
						.str("forecastJson", "Open-Meteo hourly JSON, if the server has no internet access.", false).build(),
				false, a -> {
					var std = ctx.standards();
					double lat = a.num("latitude", std.q("launchSite.latitude", Dim.DIMENSIONLESS, Double.NaN));
					double lon = a.num("longitude", std.q("launchSite.longitude", Dim.DIMENSIONLESS, Double.NaN));
					JsonObject json;
					if (a.has("forecastJson")) {
						try {
							json = JsonParser.parseString(a.str("forecastJson")).getAsJsonObject();
						} catch (RuntimeException e) {
							throw new ToolException("forecastJson is not an Open-Meteo JSON object (paste the whole response "
									+ "from api.open-meteo.com, starting with '{').");
						}
						if (Double.isNaN(lat) && json.has("latitude")) {
							lat = json.get("latitude").getAsDouble();
							lon = json.get("longitude").getAsDouble();
						}
					} else {
						if (Double.isNaN(lat) || Double.isNaN(lon)) {
							throw new ToolException("I need the launch site's GPS coordinates: give latitude and longitude in decimal "
									+ "degrees (e.g. 48.47, -81.33), or set launchSite.latitude / longitude in the team standards.");
						}
						json = Weather.fetch(lat, lon);
					}
					Weather.Forecast f = Weather.parse(json, a.str("time", null));
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("site", Units.num(lat) + ", " + Units.num(lon) + " (forecast grid elevation " + Units.fmt(f.elevation(), Dim.DISTANCE) + ")");
					out.put("time", f.time() + " (" + f.timezone() + ")");
					Map<String, Object> ground = new LinkedHashMap<>();
					ground.put("wind", Units.fmt(f.speed(), Dim.VELOCITY) + " (" + Units.num(f.speed() * 3.6) + " km/h) from "
							+ Units.num(Math.toDegrees(f.direction())) + " deg");
					if (!Double.isNaN(f.gust())) {
						ground.put("gusts", Units.fmt(f.gust(), Dim.VELOCITY) + " (" + Units.num(f.gust() * 3.6) + " km/h)");
					}
					if (!Double.isNaN(f.temperature())) {
						ground.put("temperature", Units.fmt(f.temperature(), Dim.TEMPERATURE));
					}
					if (!Double.isNaN(f.pressure())) {
						ground.put("pressure", Units.fmt(f.pressure(), Dim.PRESSURE));
					}
					double maxWind = std.rule("maxGroundWind.value", Dim.VELOCITY);
					if (!Double.isNaN(maxWind)) {
						double worst = Double.isNaN(f.gust()) ? f.speed() : Math.max(f.speed(), f.gust());
						ground.put("vsRuleLimit", (f.speed() <= maxWind ? "mean wind within " : "mean wind ABOVE ")
								+ Units.num(maxWind * 3.6) + " km/h limit" + (worst > maxWind && f.speed() <= maxWind
										? "; gusts exceed it" : ""));
					}
					out.put("ground", ground);
					double top = a.qty("top", Dim.DISTANCE, 12000);
					List<String> aloft = new ArrayList<>();
					for (Winds.Level l : f.levels()) {
						if (l.altitude() <= top) {
							aloft.add(Units.fmt(l.altitude(), Dim.DISTANCE) + " AGL: " + Units.fmt(l.speed(), Dim.VELOCITY) + " from "
									+ Units.num(Math.toDegrees(l.direction())) + " deg");
						}
					}
					out.put("windsAloft", aloft);
					if (a.bool("apply", true) && !ctx.designs.all().isEmpty()) {
						Designs.Design d = ctx.designs.get(a.str("designId", null));
						Simulation sim = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(), std);
						Weather.apply(sim.getOptions(), f, top, lat, lon);
						d.doc.setSaved(false);
						Sims.run(sim);
						Map<String, Object> fl = new LinkedHashMap<>(Sims.flightMetrics(sim.getSimulatedData()));
						out.put("flightInForecast", fl);
						out.put("applied", "wind profile, site altitude, temperature and pressure set on '" + sim.getName()
								+ "'; every tool now flies this forecast. Next: monte_carlo for the landing area, check_requirements, flight_card.");
					}
					out.put("source", "Open-Meteo forecast (open-meteo.com); winds aloft at pressure levels by geopotential height. "
							+ "Forecasts carry uncertainty: re-check on the day and use monte_carlo for dispersion.");
					return out;
				}));

		s.tool(new ToolDef("launch_planner", "Launch planner: landing areas from the ensemble forecast",
				"Where the rocket lands on launch day, from the ensemble weather forecast for the site and hour: the design flown "
						+ "many times (default 500), each flight in one of the ensemble's forecasts (ECMWF's 51, with winds at every height, "
						+ "15 days ahead; or NOAA GEFS's 31, 10 days, ground wind per forecast), with the launch angle dispersed. Returns "
						+ "the areas holding 50, 95 and 99% of landings per stage (as map polygons), every landing point, the flight in "
						+ "the forecast as it stands (trajectory, apogee, landing), apogee and flight-time spread, and the winds by "
						+ "height with the ensemble's range. Needs the site's GPS coordinates (or the team standards' launch site).",
				SimTools.simSelect(Schema.object())
						.num("latitude", "Launch site latitude, decimal degrees (default: launchSite.latitude).", false)
						.num("longitude", "Launch site longitude, decimal degrees (default: launchSite.longitude).", false)
						.str("time", "Launch time in the site's local time, e.g. \"2027-08-21T10:00\" (default: next hour).", false)
						.enumStr("model", "ecmwf (default: 51 forecasts with winds aloft, 15 days) or gfs (NOAA GEFS, 31 forecasts, 10 days).",
								false, "ecmwf", "gfs")
						.integer("runs", "Flights to simulate (default 500, at most 2000).", false)
						.qty("launchAngleSd", "Launch angle spread, 1 sd (default 1 deg).", false)
						.qty("launchDirectionSd", "Rod direction spread, 1 sd (default 5 deg).", false)
						.qty("radius", "Distance from the pad the landings should stay within (the range's recovery area), to count those beyond.", false)
						.str("ensembleJson", "Open-Meteo ensemble JSON (or a path to it), if the computer has no internet access.", false)
						.str("forecastJson", "With model gfs: Open-Meteo deterministic JSON for the winds aloft, if offline.", false)
						.bool("mapData", "Include the map's data: every landing point, the areas' outlines and the trajectory in latitude and "
								+ "longitude (default false: the numbers only; the app's map asks for it).", false)
						.str("savePath", "Also save the plan (inputs and results, JSON) here, e.g. \"plans/2027-08-21-launch.json\".", false)
						.build(),
				true, a -> {
					var std = ctx.standards();
					double lat = a.num("latitude", std.q("launchSite.latitude", Dim.DIMENSIONLESS, Double.NaN));
					double lon = a.num("longitude", std.q("launchSite.longitude", Dim.DIMENSIONLESS, Double.NaN));
					LaunchPlan.Model model = LaunchPlan.Model.of(a.str("model", null));
					String time = a.str("time", null);
					JsonObject ens;
					if (a.has("ensembleJson")) {
						ens = json(ctx, a.str("ensembleJson"), "ensembleJson");
						if ((Double.isNaN(lat) || Double.isNaN(lon)) && ens.has("latitude")) {
							lat = ens.get("latitude").getAsDouble();
							lon = ens.get("longitude").getAsDouble();
						}
					} else {
						if (Double.isNaN(lat) || Double.isNaN(lon)) {
							throw new ToolException("I need the launch site's GPS coordinates: give latitude and longitude in decimal "
									+ "degrees (e.g. 48.47, -81.33), or set launchSite.latitude / longitude in the team standards.");
						}
						if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180)) {
							throw new ToolException("Latitude must be -90..90 and longitude -180..180 (decimal degrees).");
						}
						String date = time == null || time.length() < 10 ? null : time.substring(0, 10);
						ens = LaunchPlan.fetch(LaunchPlan.url(model, lat, lon, date));
					}
					Weather.Forecast aloft = null;
					if (!model.aloft) {
						if (a.has("forecastJson")) {
							aloft = Weather.parse(json(ctx, a.str("forecastJson"), "forecastJson"), time);
						} else {
							try {
								aloft = Weather.parse(Weather.fetch(lat, lon), time);
							} catch (ToolException ex) {
								aloft = null; // offline or no hour: ground wind at every height, and the note says so
							}
						}
					}
					LaunchPlan.Ensemble e = LaunchPlan.parse(ens, model, time, aloft);
					int runs = Math.max(10, Math.min(2000, a.integer("runs", 500)));
					LaunchPlan.Settings st = new LaunchPlan.Settings(runs, a.qty("launchAngleSd", Dim.ANGLE, Math.toRadians(1)),
							a.qty("launchDirectionSd", Dim.ANGLE, Math.toRadians(5)), 20270821L, a.qtyOrNaN("radius", Dim.DISTANCE));
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Simulation base = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(), std);
					Map<String, Object> out = LaunchPlan.plan(base, d.doc, e, st, lat, lon);
					out.put("design", d.doc.getRocket().getName());
					out.put("simulation", base.getName());
					if (!a.bool("mapData", false)) {
						LaunchPlan.withoutMapData(out);
					}
					if (a.has("savePath")) {
						java.nio.file.Path p = ctx.path(a.str("savePath"));
						if (p.getParent() != null) {
							java.nio.file.Files.createDirectories(p.getParent());
						}
						Map<String, Object> saved = new LinkedHashMap<>();
						saved.put("savedAt", java.time.Instant.now().toString());
						saved.put("plan", out);
						java.nio.file.Files.writeString(p, new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(saved));
						out.put("saved", p.toAbsolutePath().toString());
					}
					return out;
				}));

		s.tool(new ToolDef("rail_buttons", "Rail button placement",
				"Lay out the rail buttons for the most rail exit speed and the cleanest departure, or check a layout. The rocket "
						+ "is guided until the AFT button leaves the rail, so the aft button goes as far aft as a body tube allows "
						+ "(over a centering ring or bulkhead when one is near): every cm it sits above the aft end is rail the "
						+ "rocket never uses (OpenRocket counts the whole rail and ignores the buttons). The forward button is "
						+ "placed where the pointing error at rail exit is least: tip-off while the rocket hangs on the aft button "
						+ "alone (gravity across the tilted rail plus the crosswind on the CP) grows with the spacing, the slop of "
						+ "the buttons in the rail slot shrinks with it. Returns both buttons' positions, the effective rail exit "
						+ "velocity, tip-off rate and angle, slop, side loads on each button, and the change from the current "
						+ "layout. apply=true moves the design's buttons there (or adds a Delrin pair); undo reverts it.",
				SimTools.overrides(SimTools.simSelect(Schema.object()))
						.qty("forward", "Check this forward button position instead of optimising (centre, measured up from the aft end).", false)
						.qty("aft", "With forward: the aft button position (centre, up from the aft end).", false)
						.qty("standoff", "Height of the rocket's aft end above the foot of the rail on the pad (default 0).", false)
						.qty("clearance", "Play of the buttons in the rail slot (default 1 mm).", false)
						.qty("crosswind", "Crosswind at the rail (default: launchSite.designWindSpeed, else 30 km/h).", false)
						.qty("minSpacing", "Smallest button spacing to consider (default one airframe diameter).", false)
						.bool("apply", "Move (or add) the design's rail buttons to the recommended positions (default false).", false)
						.build(),
				false, a -> {
					var std = ctx.standards();
					Simulation sim = SimTools.runSelected(ctx, a);
					double wind = a.qty("crosswind", Dim.VELOCITY, std.q("launchSite.designWindSpeed", Dim.VELOCITY, 30 / 3.6));
					RailButtons.Options o = new RailButtons.Options(a.qty("standoff", Dim.LENGTH, 0), a.qty("clearance", Dim.LENGTH, 0.001),
							wind, a.qty("minSpacing", Dim.LENGTH, 0), std.rule("railDepartureVelocity.min", Dim.VELOCITY));
					double[] manual = null;
					if (a.has("forward") || a.has("aft")) {
						if (!a.has("forward") || !a.has("aft")) {
							throw new ToolException("Give both forward and aft (button centres, up from the aft end) to check a layout.");
						}
						double tail = sim.getRocket().getFlightConfiguration(sim.getFlightConfigurationId()).getBoundingBoxAerodynamic().max.x;
						manual = new double[] { tail - a.qty("forward", Dim.LENGTH), tail - a.qty("aft", Dim.LENGTH) };
					}
					boolean apply = a.bool("apply", false);
					Map<String, Object> out = RailButtons.analyse(sim, o, apply, manual);
					if (apply) {
						Designs.Design d = ctx.designs.get(a.str("designId", null));
						d.doc.setSaved(false);
						Sims.run(sim);
						out.put("afterApply", Sims.flightMetrics(sim.getSimulatedData()));
					}
					return out;
				}));

		s.tool(new ToolDef("flight_card", "Launch-day flight card",
				"Write a one-page flight card (Markdown) from the simulation in the current conditions (apply weather_forecast "
						+ "first for the day's winds): vehicle, CG / CP and stability, motors with the optimum ejection delay and the "
						+ "closest available delay, predicted flight, recovery settings (events, deployment altitudes, descent "
						+ "rates), sections and landing energy, drift for each ground wind, the rule check, and sign-off lines.",
				SimTools.simSelect(Schema.object()).str("path", "Output .md path (default flight-card.md).", false).build(),
				true, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Simulation sim = SimTools.runSelected(ctx, a);
					var o = sim.getOptions();
					var std = ctx.standards();
					boolean coords = Winds.isMultiLevel(o) || !Double.isNaN(std.q("launchSite.latitude", Dim.DIMENSIONLESS, Double.NaN));
					String site = (coords ? Units.num(o.getLaunchLatitude()) + ", " + Units.num(o.getLaunchLongitude()) + ", "
							: "coordinates not set (weather_forecast or launchSite in the team standards), ")
							+ Units.fmt(o.getLaunchAltitude(), Dim.DISTANCE) + " MSL";
					return FlightCard.write(d, sim, ctx.standards(), ctx.path(a.str("path", "flight-card.md")), site);
				}));
	}

	/** JSON given inline or as a path to a file. */
	static JsonObject json(Context ctx, String v, String what) {
		String text = v.trim();
		if (!text.startsWith("{")) {
			try {
				text = java.nio.file.Files.readString(ctx.path(text));
			} catch (java.io.IOException e) {
				throw new ToolException(what + " is neither JSON nor a file I can read: " + v);
			}
		}
		try {
			return JsonParser.parseString(text).getAsJsonObject();
		} catch (RuntimeException e) {
			throw new ToolException(what + " is not an Open-Meteo JSON object (the whole response, starting with '{').");
		}
	}
}
