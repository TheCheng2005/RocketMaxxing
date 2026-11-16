package io.github.openrocketmcp.or;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.aerodynamics.AerodynamicForces;
import info.openrocket.core.aerodynamics.FlightConditions;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.listeners.AbstractSimulationListener;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Imported aerodynamic tables (RASAero II "export aero data" CSV, or any Mach / CD [/ CP] table). The drag replaces
 * OpenRocket's axial drag in every simulation of the design (power-on / power-off as the motor burns); the CP gives the
 * stability margin against the simulated CG, as Launch Canada asks for airframes with diameter changes.
 *
 * <p>A table describes one stack: the whole vehicle (the default), or, on a staged rocket, what is left flying once the
 * stages below a given stage have separated (a RASAero run of the sustainer alone). Each applies while its stack flies;
 * a stack without a table flies on OpenRocket's model.
 */
public final class AeroTable {
	private AeroTable() {
	}

	/** SI: cp in m from the nose tip (NaN when the table has no CP). Sorted by Mach, one row per Mach (lowest alpha). */
	public record Table(double[] mach, double[] cdOff, double[] cdOn, double[] cp, String source, boolean useDrag,
			double[] reference, HighSpeedAero.Settings model) {
		/** An imported table: its drag stands in for OpenRocket's. */
		public Table(double[] mach, double[] cdOff, double[] cdOn, double[] cp, String source, boolean useDrag) {
			this(mach, cdOff, cdOn, cp, source, useDrag, null, null);
		}

		/**
		 * The drag coefficient to fly with, given OpenRocket's at this instant. An imported table replaces it. The
		 * built-in model carries OpenRocket's CD at the table's conditions ({@code reference}) and applies the
		 * difference, so the Reynolds number at altitude and the angle of attack still count.
		 */
		public double flown(double m, boolean powered, double live) {
			double cd = cd(m, powered);
			if (reference == null || Double.isNaN(cd)) {
				return cd;
			}
			return Math.max(0.1 * live, live + cd - interp(mach, reference, m));
		}

		public double cd(double m, boolean powered) {
			return interp(mach, powered ? cdOn : cdOff, m);
		}

		public double cpAt(double m) {
			return interp(mach, cp, m);
		}

		public boolean hasCp() {
			for (double v : cp) {
				if (!Double.isNaN(v)) {
					return true;
				}
			}
			return false;
		}
	}

	/** Keyed by rocket id (the whole vehicle) or rocket id + "#" + the id of the stack's lowest stage. */
	private static final Map<String, Table> TABLES = new ConcurrentHashMap<>();
	/** Simulations to fly on OpenRocket's own drag; weak, so candidates that are never flown do not pile up. */
	private static final Set<Simulation> WITHOUT = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	/** The whole vehicle's table: an imported one switches the built-in model off, the model's own switches it on. */
	public static void set(Rocket r, Table t) {
		TABLES.put(r.getID().toString(), t);
		if (t.model() == null) {
			HighSpeedAero.disable(r);
		} else {
			HighSpeedAero.enable(r, t.model());
		}
	}

	/** Keeps a table as the whole vehicle's without changing whether the model is on. */
	static void put(Rocket r, Table t) {
		TABLES.put(r.getID().toString(), t);
	}

	/** The whole vehicle's table to show: the built-in model's is worked out again from the rocket as it is now. */
	public static Table shown(Rocket r) {
		try {
			HighSpeedAero.refresh(r);
		} catch (RuntimeException e) {
			// a rocket OpenRocket cannot analyse right now (no body yet): show what was kept
		}
		return of(r);
	}

	/** Every table of the rocket: the whole vehicle's and each stack's. */
	public static void clear(Rocket r) {
		String id = r.getID().toString();
		TABLES.keySet().removeIf(k -> k.equals(id) || k.startsWith(id + "#"));
		HighSpeedAero.disable(r);
	}

	/** The whole vehicle's table of a rocket or any copy of it (copies keep the original ids). */
	public static Table of(Rocket r) {
		return TABLES.get(r.getID().toString());
	}

	/**
	 * The table of the stack whose lowest stage is {@code bottom} (that stage and every stage above it). The stack that
	 * leaves the pad (bottom = the last stage) is the whole vehicle.
	 */
	public static Table of(Rocket r, AxialStage bottom) {
		return whole(r, bottom) ? of(r) : TABLES.get(r.getID() + "#" + bottom.getID());
	}

	public static void set(Rocket r, AxialStage bottom, Table t) {
		if (whole(r, bottom)) {
			set(r, t);
		} else {
			TABLES.put(r.getID() + "#" + bottom.getID(), t);
			if (HighSpeedAero.settings(r) != null) { // an imported table for one stack: the design flies on imported data
				HighSpeedAero.disable(r);
				TABLES.remove(r.getID().toString());
			}
		}
	}

	public static void clear(Rocket r, AxialStage bottom) {
		if (whole(r, bottom)) {
			TABLES.remove(r.getID().toString());
			HighSpeedAero.disable(r);
		} else {
			TABLES.remove(r.getID() + "#" + bottom.getID());
		}
	}

	static boolean whole(Rocket r, AxialStage bottom) {
		List<AxialStage> st = new ArrayList<>(r.getStageList());
		return bottom == null || st.indexOf(bottom) == st.size() - 1;
	}

	/** The whole vehicle's table, else any stack's (for naming the data in use), or null. */
	public static Table any(Rocket r) {
		Table t = of(r);
		return t != null ? t : all(r).values().stream().findFirst().orElse(null);
	}

	/** Every table of the rocket by the lowest stage of its stack, top stack first; the whole vehicle under its last stage. */
	public static Map<AxialStage, Table> all(Rocket r) {
		Map<AxialStage, Table> out = new LinkedHashMap<>();
		for (AxialStage st : r.getStageList()) {
			Table t = of(r, st);
			if (t != null) {
				out.put(st, t);
			}
		}
		return out;
	}

	/** The stack flying in a configuration: its lowest stage when it is stages 0..n, else null (a dropped booster). */
	public static AxialStage stackOf(FlightConfiguration fc) {
		List<AxialStage> st = new ArrayList<>(fc.getRocket().getStageList());
		int last = -1;
		for (int i = 0; i < st.size(); i++) {
			if (fc.isStageActive(i)) {
				if (last != i - 1) {
					return null;
				}
				last = i;
			}
		}
		return last < 0 ? null : st.get(last);
	}

	// ------------------------------------------------------------------------------------ kept with the design

	static final String FORMAT = "openrocket-mcp aero table 1";
	/** Several stacks in one file: {format, tables: [{stage, stageId, ...one table}]}; the whole vehicle has no stage. */
	static final String FORMAT2 = "openrocket-mcp aero tables 2";

	/** The file an imported table is kept in next to a design: rocket.ork -> rocket.aero.json. */
	public static Path sidecar(Path ork) {
		String n = ork.getFileName().toString();
		String stem = n.toLowerCase(Locale.ROOT).endsWith(".ork") ? n.substring(0, n.length() - 4) : n;
		return ork.resolveSibling(stem + ".aero.json");
	}

	/**
	 * Every table of a rocket as the sidecar file: the single-table format when only the whole vehicle has one (as
	 * before), else one file listing each stack's table. Null when the rocket has none.
	 */
	public static String toJson(Rocket r) {
		Map<AxialStage, Table> all = all(r);
		if (all.isEmpty()) {
			return null;
		}
		if (all.size() == 1 && of(r) != null) {
			return toJson(of(r));
		}
		JsonObject o = new JsonObject();
		o.addProperty("format", FORMAT2);
		JsonArray list = new JsonArray();
		for (Map.Entry<AxialStage, Table> e : all.entrySet()) {
			JsonObject t = tableJson(e.getValue());
			t.remove("format");
			if (!whole(r, e.getKey())) {
				t.addProperty("stage", e.getKey().getName());
				t.addProperty("stageId", e.getKey().getID().toString());
			}
			list.add(t);
		}
		o.add("tables", list);
		return new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(o);
	}

	/** Reads a sidecar file into the rocket's tables (a stack whose stage is gone is skipped and named in the result). */
	public static List<String> load(Rocket r, String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		List<String> skipped = new ArrayList<>();
		if (o.has("format") && FORMAT2.equals(o.get("format").getAsString())) {
			for (var e : o.getAsJsonArray("tables")) {
				JsonObject t = e.getAsJsonObject();
				Table table = fromJson(t);
				if (!t.has("stageId")) {
					set(r, table);
					continue;
				}
				AxialStage st = null;
				for (AxialStage s : r.getStageList()) {
					if (s.getID().toString().equals(t.get("stageId").getAsString())
							|| st == null && s.getName().equals(t.get("stage").getAsString())) {
						st = s;
					}
				}
				if (st == null) {
					skipped.add(t.get("stage").getAsString());
				} else {
					set(r, st, table);
				}
			}
			return skipped;
		}
		set(r, fromJson(json));
		return skipped;
	}

	/** SI JSON (Mach, CD power off / on, CP in m from the nose tip or null), readable and diffable. */
	public static String toJson(Table t) {
		return new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(tableJson(t));
	}

	static JsonObject tableJson(Table t) {
		JsonObject o = new JsonObject();
		o.addProperty("format", FORMAT);
		o.addProperty("source", t.source());
		o.addProperty("useDrag", t.useDrag());
		if (t.model() != null) {
			o.add("model", t.model().toJson());
		}
		JsonArray rows = new JsonArray();
		for (int i = 0; i < t.mach().length; i++) {
			JsonObject r = new JsonObject();
			r.addProperty("mach", t.mach()[i]);
			r.addProperty("cdPowerOff", t.cdOff()[i]);
			r.addProperty("cdPowerOn", t.cdOn()[i]);
			if (t.reference() != null) {
				r.addProperty("cdOpenRocket", t.reference()[i]);
			}
			if (Double.isNaN(t.cp()[i])) {
				r.add("cpFromNoseTip_m", JsonNull.INSTANCE);
			} else {
				r.addProperty("cpFromNoseTip_m", t.cp()[i]);
			}
			rows.add(r);
		}
		o.add("rows", rows);
		return o;
	}

	public static Table fromJson(String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		if (!o.has("format") || !FORMAT.equals(o.get("format").getAsString())) {
			throw new ToolException("Not an aero table saved by openrocket-mcp.");
		}
		return fromJson(o);
	}

	static Table fromJson(JsonObject o) {
		JsonArray rows = o.getAsJsonArray("rows");
		int n = rows.size();
		double[] mach = new double[n], off = new double[n], on = new double[n], cp = new double[n];
		boolean model = o.has("model") && o.get("model").isJsonObject();
		double[] ref = model ? new double[n] : null;
		for (int i = 0; i < n; i++) {
			JsonObject r = rows.get(i).getAsJsonObject();
			if (model) {
				ref[i] = r.has("cdOpenRocket") ? r.get("cdOpenRocket").getAsDouble() : Double.NaN;
			}
			mach[i] = r.get("mach").getAsDouble();
			off[i] = r.get("cdPowerOff").getAsDouble();
			on[i] = r.get("cdPowerOn").getAsDouble();
			cp[i] = r.has("cpFromNoseTip_m") && !r.get("cpFromNoseTip_m").isJsonNull() ? r.get("cpFromNoseTip_m").getAsDouble() : Double.NaN;
		}
		return new Table(mach, off, on, cp, o.get("source").getAsString(), o.get("useDrag").getAsBoolean(), ref,
				model ? HighSpeedAero.Settings.fromJson(o.getAsJsonObject("model")) : null);
	}

	/** Runs {@code sim} with OpenRocket's own drag even if the design has a table (for comparisons). */
	public static Simulation without(Simulation sim) {
		WITHOUT.add(sim);
		return sim;
	}

	/** Listener to attach for {@code sim}, or null. */
	static AbstractSimulationListener listenerFor(Simulation sim) {
		if (WITHOUT.remove(sim)) {
			return null;
		}
		Map<AxialStage, Table> use = new LinkedHashMap<>();
		if (HighSpeedAero.settings(sim.getRocket()) != null) {
			// The built-in model: worked out for this flight's rocket and configuration, each stack that can fly.
			FlightConfiguration fc = sim.getActiveConfiguration();
			Map<AxialStage, Table> model = HighSpeedAero.forFlight(fc);
			if (model.isEmpty()) {
				return null;
			}
			return new DragOverride(model, model.get(stackOf(fc)));
		}
		all(sim.getRocket()).forEach((st, t) -> {
			if (t.useDrag()) {
				use.put(st, t);
			}
		});
		return use.isEmpty() ? null : new DragOverride(use, of(sim.getRocket()));
	}

	/** A stamp that changes whenever the rocket's tables (or whether their drag applies) change. */
	static String stamp(Rocket r) {
		StringBuilder b = new StringBuilder();
		HighSpeedAero.Settings model = HighSpeedAero.settings(r);
		if (model != null) {
			return "model:" + model; // the tables themselves follow the rocket, whose own changes are stamped already
		}
		all(r).forEach((st, t) -> b.append(st.getID()).append(':').append(t.useDrag() ? System.identityHashCode(t) : 0).append(','));
		return b.toString();
	}

	/**
	 * Replaces the axial drag coefficient with the table of the stack that is flying (power-on while any motor burns).
	 * The whole vehicle's table applies while every stage it left the pad with is still on; a stack's table once the
	 * stages below it have gone. A dropped booster (or a stack without a table) flies on OpenRocket's model.
	 */
	static final class DragOverride extends AbstractSimulationListener {
		final Map<AxialStage, Table> byStack;
		final Table whole;
		double mach = Double.NaN;
		boolean powered;
		int stages = -1;

		DragOverride(Map<AxialStage, Table> byStack, Table whole) {
			this.byStack = byStack;
			this.whole = whole != null && whole.useDrag() ? whole : null;
		}

		@Override
		public FlightConditions postFlightConditions(SimulationStatus status, FlightConditions c) {
			mach = c.getMach();
			return null;
		}

		@Override
		public double postSimpleThrustCalculation(SimulationStatus status, double thrust) {
			powered = thrust > 1e-6;
			return Double.NaN;
		}

		@Override
		public AerodynamicForces postAerodynamicCalculation(SimulationStatus status, AerodynamicForces forces) {
			int active = status.getConfiguration().getActiveStageCount();
			if (stages < 0) {
				stages = active;
			}
			if (Double.isNaN(mach) || forces == null) {
				return null;
			}
			Table t;
			if (active == stages) {
				t = whole; // the vehicle as it left the pad
			} else {
				AxialStage st = stackOf(status.getConfiguration());
				t = st == null ? null : byStack.get(st);
			}
			if (t == null) {
				return null;
			}
			double cd = t.flown(mach, powered, forces.getCDaxial());
			if (Double.isNaN(cd)) {
				return null;
			}
			double ratio = forces.getCDaxial() > 0 ? cd / forces.getCDaxial() : 1;
			forces.setCDaxial(cd);
			forces.setCD(forces.getCD() * ratio);
			return forces;
		}

		@Override
		public boolean isSystemListener() {
			return true;
		}
	}

	static double interp(double[] x, double[] y, double v) {
		int n = x.length;
		if (n == 0 || Double.isNaN(v)) {
			return Double.NaN;
		}
		if (v <= x[0]) {
			return y[0];
		}
		if (v >= x[n - 1]) {
			return y[n - 1];
		}
		int i = Arrays.binarySearch(x, v);
		if (i >= 0) {
			return y[i];
		}
		i = -i - 1;
		double f = (v - x[i - 1]) / (x[i] - x[i - 1]);
		return y[i - 1] + f * (y[i] - y[i - 1]);
	}

	// ------------------------------------------------------------------------------------------- parsing

	/**
	 * Parses a CSV (comma, semicolon or tab separated). Recognized headers (case-insensitive): Mach; Alpha (only the
	 * lowest alpha is kept); CD Power-Off / CD Power-On / CD; CP (RASAero writes inches from the nose tip). Without a
	 * header row, columns are Mach, CD[, CP].
	 */
	public static Table parse(String text, String source, double cpUnit, boolean useDrag) {
		List<String[]> rows = new ArrayList<>();
		for (String line : text.split("\\r?\\n")) {
			if (!line.isBlank()) {
				rows.add(line.split("[,;\\t]"));
			}
		}
		if (rows.size() < 2) {
			throw new ToolException("The aero table needs at least two rows.");
		}
		int iMach = 0, iAlpha = -1, iOff = 1, iOn = -1, iCd = -1, iCp = -1;
		int start = 0;
		if (!isNumber(rows.get(0)[0])) {
			String[] h = rows.get(0);
			iMach = iOff = -1;
			for (int i = 0; i < h.length; i++) {
				String k = h[i].trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
				if (k.startsWith("mach")) {
					iMach = i;
				} else if (k.startsWith("alpha") || k.equals("aoa")) {
					iAlpha = i;
				} else if (k.startsWith("cd power-off") || k.startsWith("cd power off") || k.startsWith("cd (power off)")) {
					iOff = i;
				} else if (k.startsWith("cd power-on") || k.startsWith("cd power on") || k.startsWith("cd (power on)")) {
					iOn = i;
				} else if (k.equals("cd") || k.startsWith("cd ")) {
					iCd = iCd < 0 ? i : iCd;
				} else if (k.equals("cp") || k.startsWith("cp ") || k.startsWith("cp(")) {
					iCp = i;
				}
			}
			if (iOff < 0) {
				iOff = iCd;
			}
			if (iMach < 0 || iOff < 0) {
				throw new ToolException("Could not find Mach and CD columns in the header: " + String.join(",", h));
			}
			start = 1;
		} else if (rows.get(0).length >= 3) {
			iCp = 2;
		}
		// Keep the lowest-alpha row for each Mach number.
		TreeMap<Double, double[]> byMach = new TreeMap<>();
		for (int r = start; r < rows.size(); r++) {
			String[] row = rows.get(r);
			try {
				double m = num(row, iMach);
				double alpha = iAlpha >= 0 ? num(row, iAlpha) : 0;
				double off = num(row, iOff);
				double on = iOn >= 0 ? num(row, iOn) : off;
				double cp = iCp >= 0 && iCp < row.length ? num(row, iCp) * cpUnit : Double.NaN;
				double[] cur = byMach.get(m);
				if (Double.isNaN(m) || Double.isNaN(off)) {
					continue;
				}
				if (cur == null || alpha < cur[0]) {
					byMach.put(m, new double[] { alpha, off, on, cp });
				}
			} catch (RuntimeException e) {
				throw new ToolException("Bad number in aero table row " + (r + 1) + ": " + String.join(",", row));
			}
		}
		if (byMach.size() < 2) {
			throw new ToolException("The aero table needs at least two Mach numbers.");
		}
		int n = byMach.size(), i = 0;
		double[] mach = new double[n], off = new double[n], on = new double[n], cp = new double[n];
		for (Map.Entry<Double, double[]> e : byMach.entrySet()) {
			mach[i] = e.getKey();
			off[i] = e.getValue()[1];
			on[i] = e.getValue()[2];
			cp[i] = e.getValue()[3];
			i++;
		}
		for (double v : off) {
			if (!(v > 0 && v < 5)) {
				throw new ToolException("CD value " + v + " is out of range (0-5); check the column mapping.");
			}
		}
		return new Table(mach, off, on, cp, source, useDrag);
	}

	private static boolean isNumber(String s) {
		try {
			Double.parseDouble(s.trim());
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static double num(String[] row, int i) {
		return i < row.length && !row[i].isBlank() ? Double.parseDouble(row[i].trim()) : Double.NaN;
	}

	// ------------------------------------------------------------------------------------------- stability

	/** Minimum margin (cal) with the table's CP and the simulated CG, rail exit to apogee or first separation. */
	public record Margin(double min, double time, double mach) {
	}

	public static Margin minMargin(Simulation sim, Table t, double refDiameter) {
		return minMargin(sim, t, refDiameter, null);
	}

	/**
	 * The same while the stack whose lowest stage is {@code bottom} flies (null: the whole vehicle, rail exit to
	 * apogee or its first separation): from the separation of the stage below it to its own separation or apogee.
	 */
	public static Margin minMargin(Simulation sim, Table t, double refDiameter, AxialStage bottom) {
		if (!t.hasCp()) {
			return null;
		}
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		Branch br = Branch.of(b);
		double[] w = window(sim, bottom);
		if (w == null) {
			return null;
		}
		double rail = w[0], end = w[1];
		double[] cg = br.col(FlightDataType.TYPE_CG_LOCATION), mach = br.col(FlightDataType.TYPE_MACH_NUMBER);
		double best = Double.POSITIVE_INFINITY, tb = Double.NaN, mb = Double.NaN;
		for (int i = br.index(rail); i < br.size() && br.time[i] <= end; i++) {
			if (br.airspeed(i) < 30.48 || Double.isNaN(cg[i])) {
				continue;
			}
			double m = (t.cpAt(mach[i]) - cg[i]) / refDiameter;
			if (m < best) {
				best = m;
				tb = br.time[i];
				mb = mach[i];
			}
		}
		return Double.isInfinite(best) ? null : new Margin(best, tb, mb);
	}

	/**
	 * When a stack flies on the main branch: {start, end} (s). The whole vehicle from rail exit to its first separation
	 * (or apogee); a stack from the separation of the stage below it to its own separation (or apogee). Null when that
	 * stack never flies on its own.
	 */
	public static double[] window(Simulation sim, AxialStage bottom) {
		FlightDataBranch b = sim.getSimulatedData().getBranch(0);
		Rocket r = sim.getRocket();
		double rail = Sims.eventTime(b, FlightEvent.Type.LAUNCHROD), apogee = Sims.eventTime(b, FlightEvent.Type.APOGEE);
		if (Double.isNaN(rail) || Double.isNaN(apogee)) {
			return null;
		}
		List<AxialStage> st = new ArrayList<>(r.getStageList());
		int k = bottom == null ? st.size() - 1 : st.indexOf(bottom);
		if (k == st.size() - 1) {
			// The vehicle as it left the pad (whichever stages its configuration flies), until anything separates.
			double sep = Sims.eventTime(b, FlightEvent.Type.STAGE_SEPARATION);
			double end = Double.isNaN(sep) ? apogee : Math.min(sep, apogee);
			return end > rail ? new double[] { rail, end } : null;
		}
		double start = separation(b, st.get(k + 1));
		double end = k == 0 ? apogee : separation(b, st.get(k));
		if (Double.isNaN(start)) {
			return null; // the stage below never comes off: this stack never flies alone
		}
		if (Double.isNaN(end) || end > apogee) {
			end = apogee;
		}
		return end > start ? new double[] { start, end } : null;
	}

	static double separation(FlightDataBranch b, AxialStage stage) {
		for (FlightEvent e : b.getEvents()) {
			if (e.getType() == FlightEvent.Type.STAGE_SEPARATION && e.getSource() != null && e.getSource().getID().equals(stage.getID())) {
				return e.getTime();
			}
		}
		return Double.NaN;
	}

	public static String describe(Table t) {
		return t.mach().length + " Mach points " + Units.num(t.mach()[0]) + "-" + Units.num(t.mach()[t.mach().length - 1])
				+ (t.hasCp() ? ", with CP" : "") + (t.useDrag() ? ", drag applied to simulations" : ", drag not applied")
				+ " (" + t.source() + ")";
	}

	static String fmtCp(double v) {
		return Units.fmt(v, Dim.LENGTH);
	}
}
