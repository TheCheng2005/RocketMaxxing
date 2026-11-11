package io.github.openrocketmcp.calc;

/**
 * An ideal converging-diverging nozzle (one-dimensional, isentropic, constant ratio of specific heats), as in Sutton,
 * Rocket Propulsion Elements, ch. 3:
 *
 * <pre>
 *   mass flow        m = Pc At / c*
 *   area ratio       Ae/At = (1/Me) [ (2/(g+1)) (1 + (g-1)/2 Me^2) ]^((g+1)/(2(g-1)))
 *   exit pressure    pe/Pc = (1 + (g-1)/2 Me^2)^(-g/(g-1))
 *   thrust           F = Pc At [ lambda sqrt( 2g^2/(g-1) (2/(g+1))^((g+1)/(g-1)) (1 - (pe/Pc)^((g-1)/g)) ) + (pe - pa)/Pc Ae/At ]
 * </pre>
 *
 * lambda is the nozzle's efficiency on the momentum term (divergence and friction; (1 + cos a)/2 for a cone of half
 * angle a). The combustion's efficiency belongs to c*, not here.
 */
public final class Nozzle {
	private Nozzle() {
	}

	/** Exit Mach number (the supersonic one) for an area ratio Ae/At of 1 or more. */
	public static double exitMach(double areaRatio, double gamma) {
		if (!(areaRatio >= 1) || !(gamma > 1)) {
			return Double.NaN;
		}
		double lo = 1, hi = 100;
		for (int i = 0; i < 200; i++) {
			double mid = 0.5 * (lo + hi);
			if (areaRatio(mid, gamma) < areaRatio) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return 0.5 * (lo + hi);
	}

	/** Ae/At that gives exit Mach number {@code mach}. */
	public static double areaRatio(double mach, double gamma) {
		double e = (gamma + 1) / (2 * (gamma - 1));
		return Math.pow(2 / (gamma + 1) * (1 + (gamma - 1) / 2 * mach * mach), e) / mach;
	}

	/** pe/Pc at exit Mach number {@code mach}. */
	public static double pressureRatio(double mach, double gamma) {
		return Math.pow(1 + (gamma - 1) / 2 * mach * mach, -gamma / (gamma - 1));
	}

	/** Pc/pa below which the throat is not sonic and the relations above do not hold. */
	public static double chokingRatio(double gamma) {
		return Math.pow((gamma + 1) / 2, gamma / (gamma - 1));
	}

	/**
	 * Thrust coefficient F / (Pc At).
	 *
	 * @param exitOverChamber pe/Pc of this nozzle ({@link #pressureRatio} at its {@link #exitMach})
	 * @param ambientOverChamber pa/Pc
	 * @param efficiency lambda, on the momentum term
	 */
	public static double thrustCoefficient(double gamma, double areaRatio, double exitOverChamber, double ambientOverChamber,
			double efficiency) {
		double momentum = Math.sqrt(2 * gamma * gamma / (gamma - 1) * Math.pow(2 / (gamma + 1), (gamma + 1) / (gamma - 1))
				* (1 - Math.pow(exitOverChamber, (gamma - 1) / gamma)));
		return efficiency * momentum + (exitOverChamber - ambientOverChamber) * areaRatio;
	}

	/** Area ratio that expands to {@code ambientOverChamber} exactly (the most thrust at that pressure). */
	public static double optimumAreaRatio(double gamma, double ambientOverChamber) {
		double mach = Math.sqrt(2 / (gamma - 1) * (Math.pow(ambientOverChamber, -(gamma - 1) / gamma) - 1));
		return areaRatio(mach, gamma);
	}
}
