package io.github.openrocketmcp.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import info.openrocket.core.appearance.Appearance;
import info.openrocket.core.preset.ComponentPreset;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.FreeformFinSet;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.util.Coordinate;
import info.openrocket.core.util.ORColor;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.AeroTable;
import io.github.openrocketmcp.or.Analysis;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Presets;
import io.github.openrocketmcp.or.Requirements;
import io.github.openrocketmcp.report.Workbench;
import io.github.openrocketmcp.tools.Context;

/**
 * The Design page's tools: the parts tree, a part's settings and their edits, moving and copying parts, the 3-D model,
 * fin outlines, catalogue presets and appearance. Registered by {@link AppTools}.
 */
final class AppDesign {
	private AppDesign() {
	}

	// ------------------------------------------------------------------------------------------- design

	static Map<String, Object> design(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Rocket r = d.doc.getRocket();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("designId", d.id);
		out.put("name", d.name());
		out.put("path", d.path == null ? null : d.path.toString());
		out.put("saved", d.doc.isSaved());
		out.put("canUndo", d.history.undoable());
		out.put("canRedo", d.history.redoable());
		out.put("tree", node(r));
		out.put("configurations", configList(r));
		out.put("selected", r.getSelectedConfiguration().getId().toString());
		AeroTable.Table aero = AeroTable.of(r);
		out.put("aeroTable", aero == null ? null : aero.source());
		Map<String, Object> st = stability(r.getSelectedConfiguration());
		double floor = Requirements.stabilityFloor(ctx.standards(), r.getSelectedConfiguration())[0];
		if (floor > 0) {
			st.put("floor", floor); // the rule set's minimum, for the stability readout's colour
		}
		out.put("stability", st);
		if (!d.loadWarnings.isEmpty()) {
			out.put("loadWarnings", d.loadWarnings);
		}
		return out;
	}

	static Map<String, Object> node(RocketComponent c) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", c instanceof Rocket ? "rocket" : Components.shortId(c));
		m.put("name", c.getName());
		m.put("type", c.getClass().getSimpleName());
		m.put("label", c.getComponentName());
		if (c instanceof MotorMount mm && mm.isMotorMount()) {
			m.put("motorMount", true);
		}
		if (c.isMassOverridden() || c.isCGOverridden()) {
			m.put("overridden", true);
		}
		List<String> accepts = new ArrayList<>();
		for (String t : Components.CREATABLE) {
			if (c.isCompatible(Components.create(t))) {
				accepts.add(t);
			}
		}
		m.put("accepts", accepts);
		List<Map<String, Object>> kids = new ArrayList<>();
		for (RocketComponent ch : c.getChildren()) {
			kids.add(node(ch));
		}
		m.put("children", kids);
		return m;
	}

	static List<Map<String, Object>> configList(Rocket r) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (FlightConfigurationId id : r.getIds()) {
			FlightConfiguration fc = r.getFlightConfiguration(id);
			out.add(Map.of("id", id.toString(), "name", fc.getName(), "hasMotors", fc.hasMotors()));
		}
		return out;
	}

	static Map<String, Object> stability(FlightConfiguration fc) {
		Map<String, Object> m = new LinkedHashMap<>();
		try {
			Analysis.Stability st = Analysis.stability(fc, 0.3);
			m.put("length", st.length());
			m.put("diameter", st.referenceDiameter());
			m.put("cg", st.cgLaunchX());
			m.put("cgBurnout", st.cgBurnoutX());
			m.put("cp", finite(st.cpX()));
			m.put("margin", finite(st.marginCalibers()));
			m.put("marginBurnout", finite(st.burnoutMarginCalibers()));
			m.put("mass", st.launchMass());
			m.put("massBurnout", st.burnoutMass());
			m.put("mach", 0.3);
			if (!st.warnings().isEmpty()) {
				m.put("warnings", st.warnings());
			}
		} catch (RuntimeException e) {
			m.put("error", "Not enough of a rocket to analyse yet.");
		}
		return m;
	}

	static Double finite(double v) {
		return Double.isFinite(v) ? v : null;
	}

	static RocketComponent find(Designs.Design d, String id) {
		Rocket r = d.doc.getRocket();
		return "rocket".equals(id) ? r : Components.find(r, id);
	}

	static Map<String, Object> component(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		RocketComponent c = find(d, a.str("component"));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", a.str("component"));
		out.put("name", c.getName());
		out.put("type", c.getClass().getSimpleName());
		out.put("label", c.getComponentName());
		out.put("sections", Editors.render(c));
		if (!(c instanceof Rocket)) {
			out.put("paint", paint(c));
			out.put("shine", c.getAppearance() == null ? null : c.getAppearance().getShine());
			out.put("presetType", c.getPresetType() == null ? null : c.getPresetType().name());
			out.put("preset", c.getPresetComponent() == null ? null : Presets.name(c.getPresetComponent()));
		}
		try {
			out.put("mass", c.getSectionMass());
			out.put("componentMass", c.getComponentMass());
		} catch (RuntimeException ignored) {
			// mass not resolvable in this state
		}
		String w = Components.overrideWarning(c);
		if (w != null) {
			out.put("warning", w);
		}
		return out;
	}

	static Map<String, Object> edit(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		RocketComponent c = find(d, a.str("component"));
		String key = a.str("key");
		if (Editors.field(c, key) == null) {
			throw new ToolException("'" + key + "' is not a field of a " + c.getComponentName() + ".");
		}
		String done = Components.set(c, key, a.get("value"));
		d.doc.setSaved(false);
		return Map.of("applied", done);
	}

	static Map<String, Object> move(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		RocketComponent c = find(d, a.str("component"));
		RocketComponent p = c.getParent();
		if (p == null) {
			throw new ToolException("The rocket itself cannot move.");
		}
		if (a.has("parent")) {
			// Dragged in the parts list: into another part, or to a place among the new parent's parts.
			RocketComponent np = holder(d, a.str("parent"), c);
			int index = a.integer("index", np.getChildCount());
			if (np == p) {
				int from = p.getChildPosition(c);
				index = Math.max(0, Math.min(index > from ? index - 1 : index, p.getChildCount() - 1));
				if (index == from) {
					return Map.of("moved", c.getName(), "unchanged", true);
				}
				p.moveChild(c, index);
			} else {
				p.removeChild(c);
				np.addChild(c, Math.max(0, Math.min(index, np.getChildCount())));
			}
			d.doc.setSaved(false);
			return Map.of("moved", c.getName(), "into", np.getName());
		}
		int to = p.getChildPosition(c) + a.integer("by", 0);
		if (to < 0 || to >= p.getChildCount()) {
			throw new ToolException(c.getName() + " is already at the " + (to < 0 ? "top" : "bottom") + ".");
		}
		p.moveChild(c, to);
		d.doc.setSaved(false);
		return Map.of("moved", c.getName());
	}

	/** The part {@code parentId} names, checked that it can hold {@code c} and is not {@code c} or inside it. */
	static RocketComponent holder(Designs.Design d, String parentId, RocketComponent c) {
		RocketComponent np = find(d, parentId);
		for (RocketComponent x = np; x != null; x = x.getParent()) {
			if (x == c) {
				throw new ToolException(c.getName() + " cannot go inside itself.");
			}
		}
		if (!np.isCompatible(c)) {
			throw new ToolException(np.getName() + " (" + np.getComponentName() + ") cannot hold a " + c.getComponentName() + ".");
		}
		return np;
	}

	static Map<String, Object> duplicate(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		RocketComponent c = find(d, a.str("component"));
		RocketComponent p = c.getParent();
		if (p == null) {
			throw new ToolException("The rocket itself cannot be duplicated.");
		}
		RocketComponent copy = c.copy();
		if (a.has("parent")) {
			// Pasted: into the part chosen, at the place given (default its end).
			RocketComponent np = holder(d, a.str("parent"), copy);
			np.addChild(copy, Math.max(0, Math.min(a.integer("index", np.getChildCount()), np.getChildCount())));
		} else {
			p.addChild(copy, p.getChildPosition(c) + 1);
		}
		d.doc.setSaved(false);
		return Map.of("id", Components.shortId(copy), "name", copy.getName());
	}

	// ------------------------------------------------------------------------------------------- 3-D model and flight

	/** A mesh's identity: its length and a CRC of its text (the window compares, nothing else relies on it). */
	static String meshId(String mesh) {
		java.util.zip.CRC32 crc = new java.util.zip.CRC32();
		crc.update(mesh.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
		return Long.toHexString(crc.getValue()) + "-" + Integer.toHexString(mesh.length());
	}

	static Map<String, Object> model(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		Map<String, Object> out = new LinkedHashMap<>();
		java.util.Set<String> known = a.has("known") ? new java.util.HashSet<>(a.strList("known")) : java.util.Set.of();
		try {
			Map<String, Object> r = Workbench.rocket(fc);
			List<Map<String, Object>> parts = new ArrayList<>();
			for (Object o : (List<?>) r.get("parts")) {
				@SuppressWarnings("unchecked")
				Map<String, Object> p = new LinkedHashMap<>((Map<String, Object>) o);
				p.remove("props"); // the editor reads these itself, with values the app can edit
				p.remove("mass");
				// An edit usually changes one part: the window keeps the meshes (and what it built from them) by id, and
				// gets only the new ones.
				if (p.get("mesh") instanceof String mesh) {
					String id = meshId(mesh);
					p.put("meshId", id);
					if (known.contains(id)) {
						p.remove("mesh");
					}
				}
				if (p.get("id") instanceof String id) {
					RocketComponent pc = Components.find(d.doc.getRocket(), id);
					String painted = paint(pc);
					if (painted != null) {
						p.put("color", painted);
						p.put("shine", pc.getAppearance().getShine());
					}
				}
				parts.add(p);
			}
			out.put("parts", parts);
			out.put("finOut", r.get("finOut"));
		} catch (RuntimeException e) {
			out.put("parts", List.of());
		}
		out.put("stability", stability(fc));
		return out;
	}

	// ------------------------------------------------------------------------------------------- fins, catalogue, paint

	static Map<String, Object> finPoints(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		if (!(find(d, a.str("component")) instanceof FinSet f)) {
			throw new ToolException("Not a fin set.");
		}
		List<double[]> pts = new ArrayList<>();
		for (Coordinate c : f.getFinPoints()) {
			pts.add(new double[] { c.x, c.y });
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("points", pts);
		out.put("editable", f instanceof FreeformFinSet);
		out.put("bodyRadius", f.getBodyRadius());
		out.put("thickness", f.getThickness());
		return out;
	}

	static Map<String, Object> setFinPoints(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		if (!(find(d, a.str("component")) instanceof FreeformFinSet f)) {
			throw new ToolException("Only a freeform fin set's points can be edited; convert the fins first.");
		}
		JsonArray arr = a.array("points");
		if (arr.size() < 3) {
			throw new ToolException("A fin needs at least three points.");
		}
		List<Coordinate> pts = new ArrayList<>();
		for (JsonElement e : arr) {
			JsonArray xy = e.getAsJsonArray();
			double x = xy.get(0).getAsDouble(), y = xy.get(1).getAsDouble();
			if (!Double.isFinite(x) || !Double.isFinite(y) || y < -1e-9 || Math.abs(x) > 10 || y > 10) {
				throw new ToolException("Fin points are [x, y] in metres with y >= 0.");
			}
			pts.add(new Coordinate(x, y));
		}
		// The root runs from (0, 0) to (x, 0), as in OpenRocket's editor.
		Coordinate last = pts.get(pts.size() - 1);
		pts.set(0, Coordinate.ZERO);
		pts.set(pts.size() - 1, new Coordinate(last.x, 0));
		if (last.x <= 0) {
			throw new ToolException("The trailing edge of the root must be behind the leading edge.");
		}
		if (crosses(pts)) {
			throw new ToolException("The outline crosses itself; points not changed.");
		}
		f.setPoints(pts.toArray(new Coordinate[0]));
		d.doc.setSaved(false);
		return Map.of("points", pts.size(), "span", f.getSpan());
	}

	/** Whether two edges of the closed outline (the root closes it) cross; edges that share a point do not count. */
	static boolean crosses(List<Coordinate> pts) {
		int n = pts.size();
		for (int i = 0; i < n; i++) {
			for (int j = i + 2; j < n; j++) {
				if (i == 0 && j == n - 1) {
					continue; // the root and the first edge meet at the leading edge
				}
				Coordinate a = pts.get(i), b = pts.get(i + 1), c = pts.get(j), e = pts.get((j + 1) % n);
				double d1 = side(c, e, a), d2 = side(c, e, b), d3 = side(a, b, c), d4 = side(a, b, e);
				if (d1 * d2 < 0 && d3 * d4 < 0) {
					return true;
				}
			}
		}
		return false;
	}

	static double side(Coordinate p, Coordinate q, Coordinate r) {
		return (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x);
	}

	static Map<String, Object> convertFins(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		RocketComponent c = find(d, a.str("component"));
		if (!(c instanceof FinSet f) || c instanceof FreeformFinSet) {
			throw new ToolException(c instanceof FreeformFinSet ? "These fins are already freeform." : "Not a fin set.");
		}
		FreeformFinSet ff = FreeformFinSet.convertFinSet(f);
		d.doc.setSaved(false);
		return Map.of("component", Components.shortId(ff), "name", ff.getName());
	}

	static Map<String, Object> presets(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		RocketComponent c = find(d, a.str("component"));
		ComponentPreset.Type t = c.getPresetType();
		if (t == null) {
			throw new ToolException(c.getComponentName() + " parts have no catalogue.");
		}
		double own = diameterOf(c);
		List<ComponentPreset> found = Presets.search(t, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null,
				a.has("text") && !a.str("text").isBlank() ? a.str("text") : null);
		if (a.bool("sameDiameter", false) && own > 0) {
			found.removeIf(p -> !(Math.abs(Presets.outerDiameter(p) - own) <= 0.02 * own));
		}
		if (own > 0) {
			found.sort(java.util.Comparator.comparingDouble(p -> {
				double od = Presets.outerDiameter(p);
				return Double.isNaN(od) ? Double.MAX_VALUE : Math.abs(od - own);
			}));
		}
		int limit = Math.max(1, a.integer("limit", 200));
		List<Map<String, Object>> rows = new ArrayList<>();
		for (ComponentPreset p : found.subList(0, Math.min(limit, found.size()))) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("ref", Presets.name(p));
			m.put("manufacturer", p.getManufacturer().getDisplayName());
			m.put("partNo", p.getPartNo());
			m.put("description", p.has(ComponentPreset.DESCRIPTION) ? p.get(ComponentPreset.DESCRIPTION) : "");
			m.put("outerDiameter", Presets.outerDiameter(p));
			m.put("innerDiameter", p.has(ComponentPreset.INNER_DIAMETER) ? p.get(ComponentPreset.INNER_DIAMETER) : null);
			m.put("length", p.has(ComponentPreset.LENGTH) ? p.get(ComponentPreset.LENGTH) : null);
			m.put("mass", p.has(ComponentPreset.MASS) ? p.get(ComponentPreset.MASS) : null);
			m.put("material", p.has(ComponentPreset.MATERIAL) ? p.get(ComponentPreset.MATERIAL).getName() : "");
			rows.add(m);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("type", t.name());
		out.put("diameter", own > 0 ? own : null);
		out.put("current", c.getPresetComponent() == null ? null : Presets.name(c.getPresetComponent()));
		out.put("matches", found.size());
		out.put("presets", rows);
		return out;
	}

	/** The diameter a catalogue part should match: the outside of tubes and cones, the bore a ring or coupler sits in. */
	static double diameterOf(RocketComponent c) {
		for (String k : new String[] { "outerRadius", "aftRadius", "outerDiameter", "diameter", "radius" }) {
			try {
				Object v = Components.getRaw(c, k);
				if (v instanceof Double r && r > 0) {
					return k.endsWith("Radius") || k.equals("radius") ? 2 * r : r;
				}
			} catch (ToolException e) {
				// not a property of this part
			}
		}
		return Double.NaN;
	}

	static Map<String, Object> appearance(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		RocketComponent c = find(d, a.str("component"));
		String hex = a.str("color", "").trim();
		if (hex.isEmpty()) {
			c.setAppearance(null);
		} else {
			if (!hex.matches("#?[0-9a-fA-F]{6}")) {
				throw new ToolException("Colour as #rrggbb.");
			}
			int rgb = Integer.parseInt(hex.replace("#", ""), 16);
			double shine = Math.max(0, Math.min(1, a.num("shine", c.getAppearance() == null ? 0.3 : c.getAppearance().getShine())));
			Appearance old = c.getAppearance();
			c.setAppearance(new Appearance(new ORColor(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255), shine,
					old == null ? null : old.getTexture()));
		}
		d.doc.setSaved(false);
		return Map.of("component", c.getName(), "color", hex.isEmpty() ? "default" : hex);
	}

	/** The component's paint as #rrggbb, or null for OpenRocket's default look. */
	static String paint(RocketComponent c) {
		Appearance ap = c.getAppearance();
		if (ap == null || ap.getPaint() == null) {
			return null;
		}
		ORColor col = ap.getPaint();
		return String.format("#%02x%02x%02x", col.getRed(), col.getGreen(), col.getBlue());
	}
}
