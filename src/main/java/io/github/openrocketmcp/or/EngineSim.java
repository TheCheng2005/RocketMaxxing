package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

import io.github.openrocketmcp.calc.Atmosphere;
import io.github.openrocketmcp.calc.Nozzle;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * A rocket engine's burn from its design numbers, before it is fired: chamber pressure, mass flow and thrust through
 * time for a solid, a hybrid or a liquid engine. A lumped ("zero-dimensional") model, as in Sutton, Rocket Propulsion
 * Elements: at each moment the chamber is in balance between what the propellant feeds in and what the throat lets
 * out,
 *
 * <pre>
 *   Pc = (propellant mass flow) x c* / At          thrust = Cf Pc At   ({@link Nozzle})
 * </pre>
 *
 * with the combustion's characteristic velocity c* and ratio of specific heats taken from a thermochemistry code
 * (NASA CEA, RocketCEA, ProPEP) for the propellants, at the mixture ratio where they depend on it. What feeds the
 * chamber is the engine type's own:
 *
 * <ul>
 * <li><b>Solid</b>: the burning surface of BATES grains (core and, unless inhibited, both ends) regressing at
 * r = a Pc^n. Pc = (rho a c* Ab / At)^(1/(1-n)).</li>
 * <li><b>Hybrid</b>: oxidizer through an injector, m = Cd A sqrt(2 rho (Ptank - Pc)), or at a given flow; fuel from
 * the port wall regressing at r = a Gox^n, Gox the oxidizer's mass flux through the port, which widens as it burns.
 * The mixture ratio, and c* with it, shifts through the burn.</li>
 * <li><b>Liquid</b>: both propellants at a fixed mixture ratio, at a regulated chamber pressure or blowing down as
 * the tanks' gas expands, Pc = Pc0 (u / (u + x (1 - u)))^k with u the tanks' gas fraction at the start and x the
 * fraction of propellant expelled.</li>
 * </ul>
 *
 * It leaves out what a lumped model cannot see: ignition and tail-off transients, erosive burning, throat erosion,
 * two-phase flow of self-pressurising oxidizers, injector and combustion dynamics. Expect the first firing to differ
 * by several percent; then use its log (see {@link StaticFire}) in place of the prediction.
 */
public final class EngineSim {
	private EngineSim() {
	}

	/** Seconds between steps. */
	static final double DT = 0.002;
	static final double MAX_BURN = 600;
	/** Thrust builds and falls over this long, so the curve starts and ends at zero as a motor file needs. */
	static final double RAMP = 0.02;

	/**
	 * The nozzle and the combustion.
	 *
	 * @param cstar            c* at mixture ratio {@code of} (m/s), before {@code cstarEfficiency}; the argument is
	 *                         ignored where c* does not depend on it
	 * @param nozzleEfficiency on the momentum thrust
	 * @param ambient          ambient pressure (Pa) at time t from ignition
	 */
	public record Common(double throatDiameter, double expansionRatio, double gamma, DoubleUnaryOperator cstar, double cstarEfficiency,
			double nozzleEfficiency, DoubleUnaryOperator ambient) {
		double throatArea() {
			return Math.PI * throatDiameter * throatDiameter / 4;
		}
	}

	/** BATES grains: {@code count} hollow cylinders, outside inhibited by the case. Burn rate r = a Pc^n in SI. */
	public record Solid(int count, double outerDiameter, double coreDiameter, double length, boolean inhibitedEnds, double density,
			double a, double n) {
	}

	/**
	 * @param oxidizerFlow    constant oxidizer flow (kg/s), or NaN to use the injector
	 * @param injectorArea    Cd x total hole area (m^2)
	 * @param tankPressure    at the start, and {@code tankPressureEnd} when the oxidizer runs out (Pa)
	 * @param a               regression r = a Gox^n in SI (m/s, kg/m^2/s)
	 */
	public record Hybrid(double oxidizerMass, double oxidizerFlow, double injectorArea, double oxidizerDensity, double tankPressure,
			double tankPressureEnd, int ports, double portDiameter, double grainOuterDiameter, double grainLength, double fuelDensity,
			double a, double n) {
	}

	/**
	 * @param chamberPressure  at the start (Pa)
	 * @param ullage           gas fraction of the tanks at the start for a blowdown feed, or NaN for a regulated one
	 * @param polytropic       exponent of the gas's expansion (1 slow and isothermal, 1.4 fast, for nitrogen or helium 1.67)
	 */
	public record Liquid(double oxidizerMass, double fuelMass, double mixtureRatio, double chamberPressure, double ullage,
			double polytropic) {
	}

	/** One moment of the burn. */
	public record Step(double time, double thrust, double chamberPressure, double oxidizerFlow, double fuelFlow, double oxidizerLeft,
			double fuelLeft) {
		public double flow() {
			return oxidizerFlow + fuelFlow;
		}

		public double mixtureRatio() {
			return fuelFlow > 0 ? oxidizerFlow / fuelFlow : Double.NaN;
		}
	}

	/**
	 * @param steps          the burn, from the first moment the chamber is at pressure to the last
	 * @param oxidizerLoaded what was loaded (kg); for a solid, all of it is "fuel"
	 * @param ended          why the burn stopped
	 */
	public record Burn(List<Step> steps, double oxidizerLoaded, double fuelLoaded, double exitPressureRatio, String ended,
			List<String> notes) {
		public double burnTime() {
			return steps.get(steps.size() - 1).time() + 2 * RAMP;
		}

		/** Trapezoid over the curve as it is written to a motor (with its ramps up from and down to zero). */
		public double totalImpulse() {
			double[][] c = curve(Integer.MAX_VALUE);
			return StaticFire.trapezoid(c[0], c[1]);
		}

		public double propellantUsed() {
			Step last = steps.get(steps.size() - 1);
			return oxidizerLoaded + fuelLoaded - last.oxidizerLeft() - last.fuelLeft();
		}

		public double peakThrust() {
			return steps.stream().mapToDouble(Step::thrust).max().orElse(0);
		}

		public double peakPressure() {
			return steps.stream().mapToDouble(Step::chamberPressure).max().orElse(0);
		}

		/**
		 * The thrust curve for a motor: [time, thrust, oxidizer left, fuel left], from zero thrust to zero thrust, at
		 * most about {@code maxPoints} points, scaled so the reduced curve keeps the total impulse.
		 */
		public double[][] curve(int maxPoints) {
			int n = steps.size();
			int stride = Math.max(1, (int) Math.ceil(n / (double) Math.max(8, maxPoints)));
			List<Step> kept = new ArrayList<>();
			for (int i = 0; i < n; i += stride) {
				kept.add(steps.get(i));
			}
			if (kept.get(kept.size() - 1) != steps.get(n - 1)) {
				kept.add(steps.get(n - 1));
			}
			int m = kept.size() + 2;
			double[] t = new double[m], f = new double[m], ox = new double[m], fu = new double[m];
			ox[0] = oxidizerLoaded;
			fu[0] = fuelLoaded;
			for (int i = 0; i < kept.size(); i++) {
				Step s = kept.get(i);
				t[i + 1] = s.time() + RAMP;
				f[i + 1] = s.thrust();
				ox[i + 1] = s.oxidizerLeft();
				fu[i + 1] = s.fuelLeft();
			}
			t[m - 1] = t[m - 2] + RAMP;
			ox[m - 1] = ox[m - 2];
			fu[m - 1] = fu[m - 2];
			if (stride > 1) {
				double[] ft = new double[n + 2], ff = new double[n + 2];
				for (int i = 0; i < n; i++) {
					ft[i + 1] = steps.get(i).time() + RAMP;
					ff[i + 1] = steps.get(i).thrust();
				}
				ft[n + 1] = ft[n] + RAMP;
				double scale = StaticFire.trapezoid(ft, ff) / StaticFire.trapezoid(t, f);
				for (int i = 0; i < m; i++) {
					f[i] *= scale;
				}
			}
			return new double[][] { t, f, ox, fu };
		}
	}

	// ----------------------------------------------------------------------------------------------------------- solid

	public static Burn solid(Common c, Solid s) {
		if (!(s.n() < 1)) {
			throw new ToolException("A burn rate exponent of 1 or more has no steady chamber pressure (it runs away); "
					+ "propellants are below 1, usually 0.2 to 0.6.");
		}
		double web = (s.outerDiameter() - s.coreDiameter()) / 2;
		double volume = s.count() * Math.PI / 4 * (sq(s.outerDiameter()) - sq(s.coreDiameter())) * s.length();
		double loaded = volume * s.density();
		List<Step> steps = new ArrayList<>();
		List<String> notes = new ArrayList<>();
		double at = c.throatArea(), cstar = c.cstar().applyAsDouble(Double.NaN) * c.cstarEfficiency();
		Thrust th = new Thrust(c);
		double x = 0, left = loaded, t = 0, maxFlux = 0, kn0 = Double.NaN, knMax = 0;
		String ended = "the propellant burnt out";
		while (true) {
			double core = s.coreDiameter() + 2 * x, len = s.inhibitedEnds() ? s.length() : s.length() - 2 * x;
			if (x >= web - 1e-12 || len <= 0 || left <= 0) {
				break;
			}
			double ab = s.count() * (Math.PI * core * len + (s.inhibitedEnds() ? 0 : 2 * Math.PI / 4 * (sq(s.outerDiameter()) - sq(core))));
			double kn = ab / at;
			double pc = Math.pow(s.density() * s.a() * cstar * kn, 1 / (1 - s.n()));
			double f = th.at(pc, t);
			if (Double.isNaN(f)) {
				ended = "the chamber pressure fell too low for the throat to stay choked";
				break;
			}
			double r = s.a() * Math.pow(pc, s.n()), flow = s.density() * ab * r;
			if (Double.isNaN(kn0)) {
				kn0 = kn;
			}
			knMax = Math.max(knMax, kn);
			maxFlux = Math.max(maxFlux, flow / (Math.PI / 4 * sq(core)));
			steps.add(new Step(t, f, pc, 0, flow, 0, left));
			x += r * DT;
			left = Math.max(0, left - flow * DT);
			t += DT;
			if (t > MAX_BURN) {
				ended = "it was still burning after " + Units.num(MAX_BURN) + " s";
				break;
			}
		}
		requireBurn(steps, "the grains give no chamber pressure: check the burn rate, the throat and the grain sizes");
		notes.add("Kn (burning area / throat area) from " + Units.num(kn0) + " at ignition to " + Units.num(knMax) + " at most.");
		double portToThroat = Math.PI / 4 * sq(s.coreDiameter()) / at;
		if (portToThroat < 2) {
			notes.add("The core is only " + Units.num(portToThroat) + " times the throat's area: below about 2 the gas speeds up in the "
					+ "core and burns it faster than this model says (erosive burning), raising the pressure at ignition.");
		}
		if (maxFlux > 1400) {
			notes.add("Mass flux in the core reaches " + Units.num(maxFlux) + " kg/m2/s: above about 1400 (2 lb/in2/s) erosive burning is "
					+ "likely; widen the core.");
		}
		return new Burn(steps, 0, loaded, th.exitRatio, ended, notes);
	}

	// ---------------------------------------------------------------------------------------------------------- hybrid

	public static Burn hybrid(Common c, Hybrid h) {
		List<Step> steps = new ArrayList<>();
		List<String> notes = new ArrayList<>();
		double at = c.throatArea();
		Thrust th = new Thrust(c);
		double rOut = h.grainOuterDiameter() / 2, r = h.portDiameter() / 2;
		double fuelLoaded = h.fuelDensity() * h.grainLength() * (Math.PI * sq(rOut) - h.ports() * Math.PI * sq(r));
		if (!(fuelLoaded > 0)) {
			throw new ToolException("The ports take up the whole grain: " + h.ports() + " of " + Units.fmt(h.portDiameter(), Dim.LENGTH)
					+ " in a " + Units.fmt(h.grainOuterDiameter(), Dim.LENGTH) + " grain.");
		}
		// The web a port can burn through: to the case for one port; for several, until the ports would take the whole section.
		double rEnd = h.ports() == 1 ? rOut : rOut / Math.sqrt(h.ports());
		double ox = h.oxidizerMass(), fuel = fuelLoaded, t = 0, maxFlux = 0, minDrop = Double.POSITIVE_INFINITY;
		double ofMin = Double.POSITIVE_INFINITY, ofMax = 0;
		String ended = "the oxidizer ran out";
		boolean injector = Double.isNaN(h.oxidizerFlow());
		while (ox > 0) {
			if (r >= rEnd) {
				ended = "the fuel grain burnt through with " + Units.fmt(ox, Dim.MASS) + " of oxidizer left";
				break;
			}
			double tank = h.tankPressure() + (h.tankPressureEnd() - h.tankPressure()) * (1 - ox / h.oxidizerMass());
			double rr = r;
			// Fuel flow that goes with an oxidizer flow, through the port as it is now.
			DoubleUnaryOperator fuelFor = mo -> {
				double g = mo / (h.ports() * Math.PI * sq(rr));
				return h.fuelDensity() * h.ports() * 2 * Math.PI * rr * h.grainLength() * h.a() * Math.pow(g, h.n());
			};
			double mo, pc;
			if (!injector) {
				mo = h.oxidizerFlow();
				double mf = fuelFor.applyAsDouble(mo);
				pc = (mo + mf) * c.cstar().applyAsDouble(mo / mf) * c.cstarEfficiency() / at;
			} else {
				// The chamber pressure at which the injector feeds exactly what the throat passes: one crossing, by bisection.
				double lo = 0, hi = tank;
				for (int i = 0; i < 60; i++) {
					double mid = 0.5 * (lo + hi);
					double m = h.injectorArea() * Math.sqrt(2 * h.oxidizerDensity() * (tank - mid));
					double mf = fuelFor.applyAsDouble(m);
					double made = (m + mf) * c.cstar().applyAsDouble(m / mf) * c.cstarEfficiency() / at;
					if (made > mid) {
						lo = mid;
					} else {
						hi = mid;
					}
				}
				pc = 0.5 * (lo + hi);
				mo = h.injectorArea() * Math.sqrt(2 * h.oxidizerDensity() * (tank - pc));
				minDrop = Math.min(minDrop, (tank - pc) / pc);
			}
			double mf = fuelFor.applyAsDouble(mo);
			double f = th.at(pc, t);
			if (Double.isNaN(f) || !(mo > 0)) {
				ended = "the chamber pressure fell too low for the throat to stay choked";
				break;
			}
			double flux = mo / (h.ports() * Math.PI * sq(r));
			maxFlux = Math.max(maxFlux, flux);
			ofMin = Math.min(ofMin, mo / mf);
			ofMax = Math.max(ofMax, mo / mf);
			steps.add(new Step(t, f, pc, mo, mf, ox, fuel));
			r += h.a() * Math.pow(flux, h.n()) * DT;
			ox -= mo * DT;
			fuel = Math.max(0, fuel - mf * DT);
			t += DT;
			if (t > MAX_BURN) {
				ended = "it was still burning after " + Units.num(MAX_BURN) + " s";
				break;
			}
		}
		requireBurn(steps, "no oxidizer flows: check the tank pressure, the injector and the oxidizer flow");
		Step last = steps.get(steps.size() - 1);
		steps.set(steps.size() - 1, new Step(last.time(), last.thrust(), last.chamberPressure(), last.oxidizerFlow(), last.fuelFlow(),
				Math.max(0, ox), fuel));
		notes.add("Mixture ratio (oxidizer / fuel) from " + Units.num(steps.get(0).mixtureRatio()) + " at ignition to "
				+ Units.num(last.mixtureRatio()) + " at the end (range " + Units.num(ofMin) + " to " + Units.num(ofMax) + "): it shifts as the port "
				+ "widens and the oxidizer flow changes, and c* with it when a cstarTable is given.");
		notes.add(Units.fmt(fuel, Dim.MASS) + " of the " + Units.fmt(fuelLoaded, Dim.MASS) + " fuel grain is left unburnt (the port ends at "
				+ Units.fmt(2 * r, Dim.LENGTH) + " of " + Units.fmt(2 * rEnd, Dim.LENGTH) + ").");
		if (maxFlux > 650) {
			notes.add("Oxidizer mass flux in the port reaches " + Units.num(maxFlux) + " kg/m2/s: above about 650 the flame can be blown "
					+ "out or the burn turn rough; a wider port lowers it.");
		}
		if (injector && minDrop < 0.15) {
			notes.add("The injector's pressure drop falls to " + Units.num(100 * minDrop) + "% of chamber pressure: below 15 to 20% the "
					+ "chamber can drive the feed (chugging). A smaller injector area or a higher tank pressure raises it.");
		}
		if (injector) {
			notes.add("The oxidizer is taken as a liquid of constant density through the injector. A self-pressurising oxidizer (nitrous "
					+ "oxide) flashes in the injector and usually flows less than this: set the discharge coefficient from a cold flow test.");
		}
		notes.add("The burn ends when the liquid oxidizer does; the gas left in the tank gives a short, weak tail that is not modelled.");
		return new Burn(steps, h.oxidizerMass(), fuelLoaded, th.exitRatio, ended, notes);
	}

	// ---------------------------------------------------------------------------------------------------------- liquid

	public static Burn liquid(Common c, Liquid l) {
		List<Step> steps = new ArrayList<>();
		List<String> notes = new ArrayList<>();
		double at = c.throatArea(), cstar = c.cstar().applyAsDouble(l.mixtureRatio()) * c.cstarEfficiency();
		Thrust th = new Thrust(c);
		double ox = l.oxidizerMass(), fuel = l.fuelMass(), loaded = ox + fuel, t = 0;
		boolean blowdown = !Double.isNaN(l.ullage());
		String ended;
		while (true) {
			if (ox <= 0 || fuel <= 0) {
				ended = ox <= 0 && fuel <= 1e-9 * loaded ? "both propellants ran out" : ox <= 0 ? "the oxidizer ran out" : "the fuel ran out";
				break;
			}
			double x = 1 - (ox + fuel) / loaded;
			double pc = blowdown ? l.chamberPressure() * Math.pow(l.ullage() / (l.ullage() + x * (1 - l.ullage())), l.polytropic())
					: l.chamberPressure();
			double f = th.at(pc, t);
			if (Double.isNaN(f)) {
				ended = "the chamber pressure fell too low for the throat to stay choked";
				break;
			}
			double flow = pc * at / cstar, mo = flow * l.mixtureRatio() / (1 + l.mixtureRatio()), mf = flow - mo;
			steps.add(new Step(t, f, pc, mo, mf, ox, fuel));
			ox -= mo * DT;
			fuel -= mf * DT;
			t += DT;
			if (t > MAX_BURN) {
				ended = "it was still burning after " + Units.num(MAX_BURN) + " s";
				break;
			}
		}
		requireBurn(steps, "the chamber pressure is too low for the throat to choke at this ambient pressure");
		Step last = steps.get(steps.size() - 1);
		steps.set(steps.size() - 1, new Step(last.time(), last.thrust(), last.chamberPressure(), last.oxidizerFlow(), last.fuelFlow(),
				Math.max(0, ox), Math.max(0, fuel)));
		double leftOx = Math.max(0, ox), leftFuel = Math.max(0, fuel);
		if (leftOx > 0.02 * l.oxidizerMass() || leftFuel > 0.02 * l.fuelMass()) {
			notes.add("At a mixture ratio of " + Units.num(l.mixtureRatio()) + " the " + (leftOx > leftFuel ? "fuel" : "oxidizer")
					+ " runs out first: " + Units.fmt(leftOx > leftFuel ? leftOx : leftFuel, Dim.MASS) + " of "
					+ (leftOx > leftFuel ? "oxidizer" : "fuel") + " is left in its tank (carried, not burnt).");
		}
		if (blowdown) {
			notes.add("Blowdown: the chamber pressure follows the tanks' gas as it expands (from " + Units.fmt(steps.get(0).chamberPressure(),
					Dim.PRESSURE) + " to " + Units.fmt(last.chamberPressure(), Dim.PRESSURE) + "), with the injector's and lines' pressure "
					+ "drop taken as a constant share of it.");
		}
		return new Burn(steps, l.oxidizerMass(), l.fuelMass(), th.exitRatio, ended, notes);
	}

	// ---------------------------------------------------------------------------------------------------------- shared

	/** Thrust at a chamber pressure and time (for the ambient pressure), and what the nozzle does with it. */
	private static final class Thrust {
		private final Common c;
		final double exitRatio;
		private final double choke;

		Thrust(Common c) {
			this.c = c;
			if (!(c.expansionRatio() >= 1)) {
				throw new ToolException("The nozzle's exit must be at least as wide as its throat (expansion ratio "
						+ Units.num(c.expansionRatio()) + ").");
			}
			exitRatio = Nozzle.pressureRatio(Nozzle.exitMach(c.expansionRatio(), c.gamma()), c.gamma());
			choke = Nozzle.chokingRatio(c.gamma());
		}

		/** N, or NaN when the throat is not choked. */
		double at(double pc, double t) {
			double pa = c.ambient().applyAsDouble(t);
			if (!(pc > choke * pa)) {
				return Double.NaN;
			}
			return Math.max(0, Nozzle.thrustCoefficient(c.gamma(), c.expansionRatio(), exitRatio, pa / pc, c.nozzleEfficiency()))
					* pc * c.throatArea();
		}
	}

	private static void requireBurn(List<Step> steps, String why) {
		if (steps.size() < 5) {
			throw new ToolException("The engine does not burn as described: " + why + ".");
		}
	}

	/** Standard-atmosphere pressure at an altitude above sea level (m). */
	public static double pressureAt(double altitudeMsl) {
		return Atmosphere.at(altitudeMsl).pressure();
	}

	private static double sq(double v) {
		return v * v;
	}
}
