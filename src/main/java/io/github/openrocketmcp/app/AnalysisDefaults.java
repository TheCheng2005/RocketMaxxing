package io.github.openrocketmcp.app;

import java.util.LinkedHashMap;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.SimulationOptions;
import io.github.openrocketmcp.tools.Context;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.or.Winds;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * What an analysis uses for each input left empty, so the form can show it (greyed in the box) instead of an example:
 * the launch conditions of the simulation the analysis would fly, and the team's pin and opening-force coefficient.
 */
final class AnalysisDefaults {
	private AnalysisDefaults() {
	}

	static Map<String, Object> of(Context ctx, Args a) {
		Map<String, Object> out = new LinkedHashMap<>();
		Standards std = ctx.standards();
		String pin = std.defaultPin();
		if (pin != null) {
			String strength = Units.fmt(std.pinStrength(pin), Dim.FORCE);
			out.put("pinType", pin + " (" + strength + ")");
			out.put("pinStrength", strength + " (" + pin + ")");
		}
		out.put("cx", Units.num(std.q("recovery.openingForceCoefficient", Dim.DIMENSIONLESS, 1.4)));
		Designs.Design d;
		try {
			d = ctx.designs.get(a.str("designId", null));
		} catch (RuntimeException e) {
			return out; // no design open: only the team's values
		}
		Simulation sim;
		try {
			// The simulation the analysis would fly, without adding one to the design.
			sim = Sims.prepare(d, a.str("simulation", null), a.str("configuration", null), Sims.Overrides.none(), std, false);
		} catch (RuntimeException e) {
			return out; // no motor yet: no flight to read conditions from
		}
		SimulationOptions o = sim.getOptions();
		out.put("simulation", sim.getName());
		out.put("windSpeed", Units.fmt(Winds.speed(o), Dim.VELOCITY));
		out.put("windDirection", Units.fmt(Winds.direction(o), Dim.ANGLE));
		out.put("windTurbulence", Units.num(Winds.turbulence(o)));
		out.put("railLength", Units.fmt(o.getLaunchRodLength(), Dim.LENGTH));
		out.put("launchAngle", Units.fmt(o.getLaunchRodAngle(), Dim.ANGLE));
		out.put("launchDirection", o.getLaunchIntoWind() ? "into the wind" : Units.fmt(o.getLaunchRodDirection(), Dim.ANGLE));
		out.put("launchIntoWind", o.getLaunchIntoWind() ? "Yes" : "No");
		out.put("launchSiteAltitude", Units.fmt(o.getLaunchAltitude(), Dim.DISTANCE));
		out.put("latitude", Units.num(o.getLaunchLatitude()));
		out.put("longitude", Units.num(o.getLaunchLongitude()));
		out.put("temperature", o.isISAAtmosphere() ? "standard atmosphere" : Units.fmt(o.getLaunchTemperature(), Dim.TEMPERATURE));
		return out;
	}
}
