package io.github.openrocketmcp.or;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.FreeformFinSet;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.util.Coordinate;

/**
 * Scales a design or some of its parts by a factor, as OpenRocket's Edit > Scale does: every length (body lengths and
 * radii, fin chords, spans and tabs, shoulders, rail buttons, parachute diameters and lines, packed recovery sizes),
 * optionally wall thicknesses, positions and masses. Values OpenRocket works out itself ("automatic" radii) follow the
 * parts they match.
 */
public final class Scale {
	private Scale() {
	}

	/** Lengths every part may have; only those a part has (with a setter) are scaled. */
	static final List<String> LENGTHS = List.of("Length", "ForeRadius", "AftRadius", "OuterRadius", "InnerRadius", "ForeShoulderRadius",
			"ForeShoulderLength", "AftShoulderRadius", "AftShoulderLength", "ShoulderRadius", "ShoulderLength", "MotorOverhang", "RootChord",
			"TipChord", "Sweep", "Height", "TabHeight", "TabLength", "FilletRadius", "OuterDiameter", "InnerDiameter", "TotalHeight",
			"BaseHeight", "FlangeHeight", "ScrewHeight", "Diameter", "LineLength", "Radius", "StripLength", "StripWidth", "CordLength");
	static final List<String> THICKNESSES = List.of("Thickness", "ForeShoulderThickness", "AftShoulderThickness", "ShoulderThickness");
	static final List<String> OFFSETS = List.of("AxialOffset", "TabOffset", "RadialPosition", "RadialOffset", "InstanceSeparation");

	public record Options(boolean thickness, boolean offsets, boolean masses) {
	}

	/** What changed: one line per part. */
	public static List<String> scale(Rocket rocket, Set<RocketComponent> parts, double f, Options o) {
		List<String> done = new ArrayList<>();
		for (RocketComponent c : rocket) {
			if (c == rocket || parts != null && !inside(c, parts)) {
				continue;
			}
			// Read every value first and write each original times f: aliases (a nose cone's shoulder and aft shoulder)
			// and values worked out from others (a tube's inner radius from its wall) then agree.
			boolean ring = c instanceof info.openrocket.core.rocketcomponent.RadiusRingComponent;
			List<String> props = new ArrayList<>();
			for (String p : LENGTHS) {
				if (!p.equals("InnerRadius") || ring || o.thickness()) {
					props.add(p);
				}
			}
			if (o.thickness() && !ring) {
				props.addAll(THICKNESSES);
			}
			if (o.offsets() && !(parts != null && parts.contains(c))) { // a part asked for keeps its place; what is inside it moves with it
				props.addAll(OFFSETS);
			}
			java.util.Map<String, Double> was = new java.util.LinkedHashMap<>();
			for (String p : props) {
				Double v = read(c, p);
				if (v != null) {
					was.put(p, v);
				}
			}
			int n = 0;
			for (var e : was.entrySet()) {
				n += write(c, e.getKey(), e.getValue() * f);
			}
			if (o.offsets() && !(parts != null && parts.contains(c)) && c.isCGOverridden()) {
				c.setOverrideCGX(c.getOverrideCGX() * f);
				n++;
			}
			if (c instanceof FreeformFinSet ff) {
				Coordinate[] pts = ff.getFinPoints();
				Coordinate[] scaled = new Coordinate[pts.length];
				for (int i = 0; i < pts.length; i++) {
					scaled[i] = new Coordinate(pts[i].x * f, pts[i].y * f, pts[i].z * f);
				}
				ff.setPoints(scaled);
				n++;
			}
			if (o.masses()) {
				double m3 = f * f * f;
				if (c.isMassOverridden()) {
					c.setOverrideMass(c.getOverrideMass() * m3);
					n++;
				}
				if (c instanceof MassComponent mc) {
					mc.setComponentMass(mc.getComponentMass() * m3);
					n++;
				}
			}
			if (n > 0) {
				done.add(c.getName() + ": " + n + " value" + (n == 1 ? "" : "s"));
			}
		}
		return done;
	}

	/** The largest body tube diameter: what "scale to this diameter" matches. */
	public static double bodyDiameter(Rocket rocket) {
		double d = 0;
		for (RocketComponent c : rocket) {
			if (c instanceof BodyTube b) {
				d = Math.max(d, 2 * b.getOuterRadius());
			}
		}
		return d;
	}

	/** Nose tip to the aft end of the last part. */
	public static double length(Rocket rocket) {
		double max = 0;
		for (RocketComponent c : rocket) {
			if (c.getParent() != null && c.getParent().getParent() == rocket) { // the body parts of each stage
				for (Coordinate at : c.getComponentLocations()) {
					max = Math.max(max, at.x + c.getLength());
				}
			}
		}
		return max;
	}

	private static boolean inside(RocketComponent c, Set<RocketComponent> parts) {
		for (RocketComponent p = c; p != null; p = p.getParent()) {
			if (parts.contains(p)) {
				return true;
			}
		}
		return false;
	}

	/** A double property's value, or null if the part has none, cannot set it, or OpenRocket works it out itself. */
	private static Double read(RocketComponent c, String prop) {
		Method get = method(c, "get" + prop, 0), set = method(c, "set" + prop, 1);
		if (get == null || set == null || get.getReturnType() != double.class) {
			return null;
		}
		Method auto = method(c, "is" + prop + "Automatic", 0);
		try {
			if (auto != null && Boolean.TRUE.equals(auto.invoke(c))) {
				return null;
			}
			double v = (double) get.invoke(c);
			return v == 0 || Double.isNaN(v) ? null : v;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static int write(RocketComponent c, String prop, double v) {
		try {
			method(c, "set" + prop, 1).invoke(c, v);
			return 1;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return 0; // a value the part does not take in its current state
		}
	}

	private static Method method(RocketComponent c, String name, int args) {
		for (Method m : c.getClass().getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == args && (args == 0 || m.getParameterTypes()[0] == double.class)) {
				return m;
			}
		}
		return null;
	}
}
