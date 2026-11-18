package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.Parachute;
import io.github.openrocketmcp.calc.Atmosphere;
import io.github.openrocketmcp.calc.Charges;
import io.github.openrocketmcp.calc.OpeningShock;
import io.github.openrocketmcp.calc.Parachutes;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * The recovery chain for a simulated flight: deployment conditions -> opening loads -> shear pins, plus
 * descent-rate and landing-energy checks for every recovery device in every branch.
 */
public final class Recovery {
	private Recovery() {
	}

	/** Loads for one deployment, SI. */
	public record Loads(double infiniteMass, double finiteMass, double steadyDrag, double massRatio,
			double fillTime, double design, String designMethod) {
	}

	public static Loads loads(Sims.Deployment d, double otherCdA, Standards std, double cxOverride) {
		double cd = d.device().getCD();
		double cdA = cd * d.device().getArea();
		double cx = Double.isNaN(cxOverride) ? std.q("recovery.openingForceCoefficient", Dim.DIMENSIONLESS, 1.4) : cxOverride;
		double inf = OpeningShock.infiniteMass(d.density(), d.airspeed(), cdA, cx);
		double diameter = d.device() instanceof Parachute p ? p.getDiameter() : Math.sqrt(4 * d.device().getArea() / Math.PI);
		double pathAngle = Math.atan2(-d.verticalSpeed(), Math.max(1e-6, Math.abs(d.horizontalSpeed())));
		OpeningShock.Inflation fin = OpeningShock.inflation(d.mass(), cdA, otherCdA, d.density(), d.airspeed(),
				pathAngle, diameter, std.q("recovery.canopyFillConstant", Dim.DIMENSIONLESS, 4),
				std.q("recovery.inflationExponent", Dim.DIMENSIONLESS, 1));
		String method = std.str("recovery.openingLoadMethod", "infinite_mass");
		double opening = switch (method) {
			case "finite_mass" -> fin.peakForce();
			case "max" -> Math.max(inf, fin.peakForce());
			default -> inf;
		};
		// Early (low-speed) deployments are governed by the steady drag once the vehicle reaches terminal velocity
		// under the device, which equals its weight.
		double steady = d.mass() * Atmosphere.G0;
		double design = Math.max(opening, steady);
		String label = opening >= steady ? method : method + ", floored at steady-descent drag";
		return new Loads(inf, fin.peakForce(), steady, OpeningShock.massRatio(d.density(), cdA, d.mass()),
				fin.fillTime(), design, label);
	}

	public static Map<String, Object> renderLoads(Loads l) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("openingLoadInfiniteMass(Cx)", Units.fmt(l.infiniteMass(), Dim.FORCE));
		m.put("openingLoadFiniteMass(inflation)", Units.fmt(l.finiteMass(), Dim.FORCE) + " (fill time "
				+ Units.num(l.fillTime()) + " s)");
		m.put("steadyDescentDrag", Units.fmt(l.steadyDrag(), Dim.FORCE));
		m.put("knackeMassRatio", Units.num(l.massRatio()) + (l.massRatio() < 0.1
				? " (heavy payload: infinite-mass Cx method applies)"
				: " (light payload: canopy fills while vehicle slows; finite-mass load is the more realistic one)"));
		m.put("designLoad", Units.fmt(l.design(), Dim.FORCE) + " [" + l.designMethod() + "]");
		return m;
	}

	/** Role of each deployment within its branch: first of several = drogue, last = main. */
	static String role(List<Sims.Deployment> branchDeps, int index) {
		if (branchDeps.size() == 1) {
			return "main (single event)";
		}
		return index == branchDeps.size() - 1 ? "main" : index == 0 ? "drogue" : "intermediate";
	}

	/**
	 * Full recovery analysis of a simulated flight. {@code pinStrength} NaN skips shear pin sizing.
	 */
	public static Map<String, Object> analyze(Simulation sim, Standards std, double pinStrength, String pinName,
			double cxOverride) {
		List<Sims.Deployment> all = Sims.deployments(sim);
		Map<String, List<Sims.Deployment>> byBranch = new LinkedHashMap<>();
		for (Sims.Deployment d : all) {
			byBranch.computeIfAbsent(d.branch(), k -> new ArrayList<>()).add(d);
		}
		double pinSf = std.q("recovery.shearPinHoldSafetyFactor", Dim.DIMENSIONLESS, 2);
		List<Map<String, Object>> rows = new ArrayList<>();
		List<Map<String, Object>> summary = new ArrayList<>();
		for (Map.Entry<String, List<Sims.Deployment>> e : byBranch.entrySet()) {
			List<Sims.Deployment> deps = e.getValue();
			double otherCdA = 0;
			for (int i = 0; i < deps.size(); i++) {
				Sims.Deployment d = deps.get(i);
				Map<String, Object> row = Sims.renderDeployment(d);
				row.put("role", role(deps, i));
				Loads l = loads(d, otherCdA, std, cxOverride);
				row.putAll(renderLoads(l));
				double harnessSf = std.q("recovery.harnessSafetyFactor", Dim.DIMENSIONLESS, 2);
				row.put("harnessWorkingLoad", Units.fmt(l.design() * harnessSf, Dim.FORCE) + " (design load x " + Units.num(harnessSf)
						+ ": rating needed for shock cord, quick links, swivels, eye bolts)");
				boolean laterEvent = i < deps.size() - 1;
				// The numbers a team acts on, one line per device: what to rate the harness for, how fast it comes
				// down, how many pins hold the next bay shut. The rest of the row shows how they were found.
				Map<String, Object> key = new LinkedHashMap<>();
				key.put("device", d.device().getName() + (byBranch.size() > 1 ? " (" + e.getKey() + ")" : ""));
				key.put("role", role(deps, i));
				key.put("openingLoad", Units.fmt(l.design(), Dim.FORCE));
				key.put("harnessRating", Units.fmt(l.design() * std.q("recovery.harnessSafetyFactor", Dim.DIMENSIONLESS, 2), Dim.FORCE));
				key.put("descentRate", Units.fmt(d.steadyDescentRate(), Dim.VELOCITY));
				key.put("deploySpeed", Units.fmt(d.airspeed(), Dim.VELOCITY));
				summary.add(key);
				if (!Double.isNaN(pinStrength) && laterEvent) {
					int pins = Math.max(3, Charges.shearPinsToHold(l.design(), pinStrength, pinSf));
					key.put("shearPins", pins + " x " + (pinName == null ? Units.fmt(pinStrength, Dim.FORCE) : pinName + " (" + Units.fmt(pinStrength, Dim.FORCE) + ")"));
					row.put("shearPinsForLaterBays", pins + " x " + (pinName == null ? Units.fmt(pinStrength, Dim.FORCE) + " pins" : pinName)
							+ " (safety factor " + Units.num(pinSf) + ", min 3): bays that must stay closed while this device opens");
				}
				if (!Double.isNaN(d.timeAfterApogee()) && d.timeAfterApogee() < -0.5) {
					row.put("WARNING", "deploys " + Units.num(-d.timeAfterApogee()) + " s BEFORE apogee; check the deployment event");
				}
				if (i == deps.size() - 1) {
					double v = d.steadyDescentRate();
					row.put("landingKineticEnergy(all tethered mass)", Units.fmt(Parachutes.kineticEnergy(d.mass(), v), Dim.ENERGY));
				}
				otherCdA += d.device().getCD() * d.device().getArea();
				rows.add(row);
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("summary", summary);
		out.put("simulation", sim.getName());
		out.put("deployments", rows);
		List<String> notes = new ArrayList<>();
		if (rows.isEmpty()) {
			// Nothing opened: the rocket comes down ballistic. Say so instead of returning an empty table.
			out.put("WARNING", "No recovery device opened in this simulation: the rocket lands ballistic at "
					+ Units.fmt(Math.abs(sim.getSimulatedData().getGroundHitVelocity()), Dim.VELOCITY) + ". Add a parachute or set its "
					+ "deployment (set_deployment), and check get_design for parachutes with no area.");
		}
		notes.add("Airspeed at deployment comes from the OpenRocket simulation (Mach x speed of sound, so wind and horizontal velocity are included).");
		notes.add("CdA uses OpenRocket's convention: Cd referenced to the nominal canopy area. If you enter a vendor Cd referenced to projected area (e.g. 2.2), use the projected area too.");
		notes.add("Infinite-mass load = Cx * q * CdA (Knacke). Finite-mass load integrates vehicle deceleration while the canopy fills over n*D0/v; n and the inflation exponent come from the team standards and should be calibrated against test data.");
		notes.add("Landing energy uses the whole mass under the last device. Rules usually apply per independently tethered section: use descent_energy with section masses.");
		out.put("notes", notes);
		return out;
	}
}
