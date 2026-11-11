package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.openrocketmcp.calc.Nozzle;
import io.github.openrocketmcp.mcp.ToolException;

/** The engine model against compressible-flow tables and against burns that have a closed-form answer. */
class EngineSimTest {
	static final double G0 = 9.80665;

	static EngineSim.Common common(double throat, double eps, double gamma, double cstar, double pa) {
		return new EngineSim.Common(throat, eps, gamma, of -> cstar, 1, 1, t -> pa);
	}

	/** Isentropic flow tables (NACA Report 1135, gamma 1.4): M = 2: A/A* 1.6875, p/p0 0.12780; M = 3: 4.2346, 0.027224. */
	@Test
	void nozzleMatchesTheIsentropicTables() {
		assertEquals(1.6875, Nozzle.areaRatio(2, 1.4), 1e-4);
		assertEquals(4.2346, Nozzle.areaRatio(3, 1.4), 1e-4);
		assertEquals(0.12780, Nozzle.pressureRatio(2, 1.4), 1e-5);
		assertEquals(0.027224, Nozzle.pressureRatio(3, 1.4), 1e-6);
		assertEquals(3, Nozzle.exitMach(4.2346, 1.4), 1e-4);
		assertEquals(1.8929, Nozzle.chokingRatio(1.4), 1e-4, "critical pressure ratio");
		// Worked by hand for k = 1.30 expanding 2.068 MPa to 0.1013 MPa (pe/Pc = 0.04898):
		// CF = sqrt(2k^2/(k-1) x (2/(k+1))^((k+1)/(k-1)) x (1 - (pe/Pc)^((k-1)/k))) = sqrt(11.267 x 0.3425 x 0.5015) = 1.391,
		// exit Mach sqrt(2/(k-1) x ((pe/Pc)^-((k-1)/k) - 1)) = 2.590, area ratio 3.258.
		double pr = 0.1013 / 2.068;
		double eps = Nozzle.optimumAreaRatio(1.30, pr);
		assertEquals(1.391, Nozzle.thrustCoefficient(1.30, eps, pr, pr, 1), 0.001);
		assertEquals(3.258, eps, 0.005, "its area ratio");
		assertEquals(2.590, Nozzle.exitMach(eps, 1.30), 0.002);
		// Vacuum adds pe/Pc x Ae/At; a nozzle at its design pressure gives the most thrust there.
		assertEquals(Nozzle.thrustCoefficient(1.30, eps, pr, pr, 1) + pr * eps, Nozzle.thrustCoefficient(1.30, eps, pr, 0, 1), 1e-12);
		assertTrue(Nozzle.thrustCoefficient(1.30, eps * 1.5, Nozzle.pressureRatio(Nozzle.exitMach(eps * 1.5, 1.30), 1.30), pr, 1)
				< Nozzle.thrustCoefficient(1.30, eps, pr, pr, 1));
	}

	/** A regulated liquid: constant everything. F = Cf Pc At, flow = Pc At / c*, burn time = propellant / flow. */
	@Test
	void regulatedLiquidIsConstant() {
		double throat = 0.03, at = Math.PI * throat * throat / 4, pc = 2.0e6, cstar = 1700, pa = 101325;
		EngineSim.Burn b = EngineSim.liquid(common(throat, 4, 1.2, cstar, pa), new EngineSim.Liquid(6, 3, 2.0, pc, Double.NaN, 1.2));
		double flow = pc * at / cstar;
		double cf = Nozzle.thrustCoefficient(1.2, 4, Nozzle.pressureRatio(Nozzle.exitMach(4, 1.2), 1.2), pa / pc, 1);
		EngineSim.Step mid = b.steps().get(b.steps().size() / 2);
		assertEquals(cf * pc * at, mid.thrust(), 1e-6 * cf * pc * at);
		assertEquals(flow, mid.flow(), 1e-9);
		assertEquals(2.0, mid.mixtureRatio(), 1e-9);
		assertEquals(9 / flow, b.burnTime(), 0.05);
		assertEquals(cf * pc * at * 9 / flow, b.totalImpulse(), 0.01 * cf * pc * at * 9 / flow, "impulse = thrust x time");
		assertEquals(9, b.propellantUsed(), 0.01, "all of it burnt");
		// Delivered specific impulse = Cf c* / g0.
		assertEquals(cf * cstar / G0, b.totalImpulse() / (b.propellantUsed() * G0), 0.01 * cf * cstar / G0);
		// The reduced curve keeps the impulse, starts and ends at zero, and the masses fall to nothing.
		double[][] c = b.curve(100);
		assertTrue(c[0].length <= 110);
		assertEquals(b.totalImpulse(), StaticFire.trapezoid(c[0], c[1]), 1e-6 * b.totalImpulse());
		assertEquals(0, c[1][0], 0);
		assertEquals(0, c[1][c[1].length - 1], 0);
		assertEquals(6, c[2][0], 1e-12);
		assertEquals(0, c[2][c[2].length - 1] + c[3][c[3].length - 1], 0.01);
	}

	/** Blowdown: when everything is expelled the gas has expanded from the ullage to the whole tank: Pc = Pc0 u^k. */
	@Test
	void blowdownEndsAtTheExpandedGasPressure() {
		EngineSim.Burn b = EngineSim.liquid(common(0.03, 4, 1.2, 1700, 20000),
				new EngineSim.Liquid(6, 3, 2.0, 2.0e6, 0.4, 1.2));
		EngineSim.Step first = b.steps().get(0), last = b.steps().get(b.steps().size() - 1);
		assertEquals(2.0e6, first.chamberPressure(), 1);
		assertEquals(2.0e6 * Math.pow(0.4, 1.2), last.chamberPressure(), 0.005 * 2.0e6);
		assertTrue(last.thrust() < first.thrust());
		assertEquals(9, b.propellantUsed(), 0.02);
		// A mixture ratio that does not match what was loaded leaves the surplus in its tank.
		EngineSim.Burn lean = EngineSim.liquid(common(0.03, 4, 1.2, 1700, 20000), new EngineSim.Liquid(6, 3, 1.5, 2.0e6, Double.NaN, 1.2));
		assertEquals(3 + 4.5, lean.propellantUsed(), 0.02, "3 kg of fuel burns with 4.5 kg of oxidizer at 1.5");
		assertEquals(1.5, lean.steps().get(lean.steps().size() - 1).oxidizerLeft(), 0.02);
		assertTrue(lean.ended().contains("fuel ran out"), lean.ended());
		assertTrue(lean.notes().stream().anyMatch(n -> n.contains("left in its tank")));
	}

	/** A solid with inhibited ends burns its core outward: Pc = (rho a c* Kn)^(1/(1-n)) at every moment. */
	@Test
	void solidFollowsTheKnRelation() {
		double rho = 1750, n = 0.35, a = 5e-3 / Math.pow(1e6, n); // 5 mm/s at 1 MPa
		double throat = 0.02, at = Math.PI * throat * throat / 4, cstar = 1500;
		EngineSim.Solid grain = new EngineSim.Solid(3, 0.070, 0.025, 0.12, true, rho, a, n);
		EngineSim.Burn b = EngineSim.solid(common(throat, 6, 1.2, cstar, 101325), grain);
		double kn0 = 3 * Math.PI * 0.025 * 0.12 / at;
		assertEquals(Math.pow(rho * a * cstar * kn0, 1 / (1 - n)), b.steps().get(0).chamberPressure(), 1);
		// Progressive: the core widens, so pressure and thrust rise to the end.
		assertTrue(b.steps().get(b.steps().size() - 1).chamberPressure() > 1.5 * b.steps().get(0).chamberPressure());
		double loaded = rho * 3 * Math.PI / 4 * (0.070 * 0.070 - 0.025 * 0.025) * 0.12;
		assertEquals(loaded, b.fuelLoaded(), 1e-9);
		assertEquals(loaded, b.propellantUsed(), 0.01 * loaded, "mass is conserved: what flowed out is what was there");
		// Every step: flow out of the throat equals flow off the grain.
		for (EngineSim.Step s : b.steps()) {
			assertEquals(s.chamberPressure() * at / cstar, s.flow(), 1e-6 * s.flow());
		}
		// BATES grains (ends burning) of the right proportions are close to neutral: far flatter than the core-only burn.
		EngineSim.Burn bates = EngineSim.solid(common(throat, 6, 1.2, cstar, 101325), new EngineSim.Solid(3, 0.070, 0.025, 0.12, false, rho, a, n));
		double lo = bates.steps().stream().mapToDouble(EngineSim.Step::chamberPressure).min().orElse(0);
		assertTrue(bates.peakPressure() / lo < 1.35, "BATES pressure stays within 35%: " + bates.peakPressure() / lo);
		assertEquals(loaded, bates.propellantUsed(), 0.01 * loaded);
		assertThrows(ToolException.class, () -> EngineSim.solid(common(throat, 6, 1.2, cstar, 101325),
				new EngineSim.Solid(3, 0.070, 0.025, 0.12, true, rho, a, 1.0)), "an exponent of 1 has no steady pressure");
	}

	/**
	 * A hybrid at a constant oxidizer flow, one port: r' = a (mo / (pi r^2))^n integrates to
	 * r^(2n+1) = r0^(2n+1) + (2n+1) a (mo/pi)^n t.
	 */
	@Test
	void hybridPortGrowsAsTheClosedFormSays() {
		double a = 0.155e-3, n = 0.5, mo = 0.6, r0 = 0.015, rhoF = 920, len = 0.4;
		EngineSim.Hybrid h = new EngineSim.Hybrid(6.0, mo, Double.NaN, 750, 5e6, 5e6, 1, 2 * r0, 0.09, len, rhoF, a, n);
		EngineSim.Burn b = EngineSim.hybrid(common(0.022, 4, 1.25, 1500, 101325), h);
		assertEquals(6.0 / mo, b.burnTime(), 0.06, "the oxidizer lasts mass / flow");
		double t = 6.0 / mo;
		double r = Math.pow(Math.pow(r0, 2 * n + 1) + (2 * n + 1) * a * Math.pow(mo / Math.PI, n) * t, 1 / (2 * n + 1));
		double burnt = rhoF * len * Math.PI * (r * r - r0 * r0);
		EngineSim.Step last = b.steps().get(b.steps().size() - 1);
		assertEquals(burnt, b.fuelLoaded() - last.fuelLeft(), 0.01 * burnt, "fuel burnt, from the port's final radius");
		// The port widens, so the fuel flow falls and the mixture ratio rises (for n = 0.5 the flow is constant; above, it falls).
		assertEquals(b.steps().get(0).fuelFlow(), last.fuelFlow(), 0.01 * last.fuelFlow(), "n = 0.5: fuel flow does not change");
		EngineSim.Burn steep = EngineSim.hybrid(common(0.022, 4, 1.25, 1500, 101325),
				new EngineSim.Hybrid(6.0, mo, Double.NaN, 750, 5e6, 5e6, 1, 2 * r0, 0.09, len, rhoF, 0.05e-3, 0.8));
		assertTrue(steep.steps().get(steep.steps().size() - 1).mixtureRatio() > steep.steps().get(0).mixtureRatio());
		// Chamber pressure is what the flow makes of the throat.
		EngineSim.Step s = b.steps().get(10);
		assertEquals(s.flow() * 1500 / (Math.PI * 0.022 * 0.022 / 4), s.chamberPressure(), 1e-6 * s.chamberPressure());
	}

	/** Through an injector, the chamber settles where the injector's flow is the throat's: both relations hold at once. */
	@Test
	void hybridInjectorBalancesTheChamber() {
		double cda = 0.4 * 12 * Math.PI * 0.0015 * 0.0015 / 4, rhoOx = 750, tank = 5.0e6, throat = 0.022;
		EngineSim.Hybrid h = new EngineSim.Hybrid(5.0, Double.NaN, cda, rhoOx, tank, 3.5e6, 1, 0.03, 0.09, 0.4, 920, 0.155e-3, 0.5);
		EngineSim.Burn b = EngineSim.hybrid(common(throat, 4, 1.25, 1500, 101325), h);
		EngineSim.Step first = b.steps().get(0), last = b.steps().get(b.steps().size() - 1);
		assertEquals(cda * Math.sqrt(2 * rhoOx * (tank - first.chamberPressure())), first.oxidizerFlow(), 1e-6);
		assertEquals(first.flow() * 1500 / (Math.PI * throat * throat / 4), first.chamberPressure(), 1e-3 * first.chamberPressure());
		assertTrue(first.chamberPressure() < tank && first.chamberPressure() > 0.2 * tank);
		assertTrue(last.thrust() < first.thrust(), "the tank pressure falls, and the thrust with it");
		assertEquals(5.0, b.oxidizerLoaded() - last.oxidizerLeft(), 0.02, "all the oxidizer is used");
		// A grain too thin for the oxidizer burns through first.
		EngineSim.Burn thin = EngineSim.hybrid(common(throat, 4, 1.25, 1500, 101325),
				new EngineSim.Hybrid(30.0, 0.6, Double.NaN, rhoOx, tank, tank, 1, 0.03, 0.045, 0.4, 920, 0.155e-3, 0.5));
		assertTrue(thin.ended().contains("burnt through"), thin.ended());
	}

	/** Thrust rises as the air thins: at altitude the same engine gives pa x Ae more. */
	@Test
	void thrustRisesWithAltitude() {
		double throat = 0.03, at = Math.PI * throat * throat / 4;
		EngineSim.Liquid l = new EngineSim.Liquid(6, 3, 2.0, 2.0e6, Double.NaN, 1.2);
		double sea = EngineSim.liquid(common(throat, 4, 1.2, 1700, 101325), l).steps().get(5).thrust();
		double high = EngineSim.liquid(common(throat, 4, 1.2, 1700, EngineSim.pressureAt(10000)), l).steps().get(5).thrust();
		assertEquals((101325 - EngineSim.pressureAt(10000)) * 4 * at, high - sea, 1e-6 * sea);
		assertThrows(ToolException.class, () -> EngineSim.liquid(common(throat, 4, 1.2, 1700, 101325),
				new EngineSim.Liquid(6, 3, 2.0, 1.5e5, Double.NaN, 1.2)), "1.5 bar in the chamber does not choke the throat at sea level");
	}
}
