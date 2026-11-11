package io.github.openrocketmcp;

import java.util.List;

import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.McpServer.Prompt;
import io.github.openrocketmcp.mcp.McpServer.PromptArg;
import io.github.openrocketmcp.mcp.McpServer.Resource;
import io.github.openrocketmcp.tools.Context;

/** Guided workflows (MCP prompts) and reference resources. */
final class Prompts {
	private Prompts() {
	}

	static final String METHODS = """
			# Methods used by openrocket-mcp

			## Flight
			OpenRocket {openRocketVersion} six-degree-of-freedom simulation (Barrowman aerodynamics). Stability margin = (CP - CG) / max body
			diameter. Launch Canada requires RASAero CP/CD overrides when the airframe diameter changes (DTEG R10.3.1).
			The server caches what OpenRocket recomputes at every step without change (airframe mass and inertia between
			stagings, motor mass before ignition and after burnout, the geometry check): identical results, about 3x faster.

			## Atmosphere
			International Standard Atmosphere to 20 km: T = 288.15 - 0.0065 h, p = 101325 (T/288.15)^5.2559, rho = p / (287.05 T).

			## Descent
			v = sqrt(2 m g / (rho Cd A)). Cd and A must share a reference area (nominal for OpenRocket and most vendors;
			projected for some vendor Cd values such as 2.2).

			## Parachute size chart
			v = sqrt(2 m g / (rho (n Cd A + CdA_other))). From a design, m is the simulated mass under the device and
			CdA_other = 2 m g / (rho v_sim^2) - n Cd A of the parachute as it is now, so the curve passes through the simulated
			rate (airframe drag and any parachute still attached included), read where check_requirements reads it: the
			middle of the steady part of that descent.

			## Opening load (Knacke, Parachute Recovery Systems Design Manual, NWC TP 6575, DTIC ADA247666)
			- Infinite mass: F = Cx * 0.5 rho v^2 * Cd A. Cx from Knacke table 5-1 (team default 1.4).
			- Finite mass: integrate the vehicle's 2D point-mass motion while CdA(t) = CdA (t/t_f)^j, t_f = n D0 / v0.
			  n (canopy fill constant) and j come from team standards; calibrate from test data.
			- Mass ratio Rm = rho CdA^1.5 / m indicates which regime applies (small: infinite mass; large: finite mass).
			- v is the airspeed at deployment from the simulation (vertical + horizontal + wind), not only the vertical speed.

			## Shear pins
			n = ceil(F_design * SF / F_pin), at least 3 evenly spaced (DTEG 8.8.3).

			## Black powder
			Ideal gas: m = P V / (R T), R = 266 in lbf / (lbm R), T = 3307 R, i.e. grams = P[psi] V[in^3] / 879,662 * 453.6
			(~0.006 D^2 L grams at 15 psi). P = (pins * F_pin + extra) * SF / bulkhead area. Backup = primary * factor.
			Charge well volume <= 2 x charge volume (DTEG 8.8.4). Always ground test.

			## Bay volume
			Packed volume = vendor packing volume + cord width x thickness x length, times a packing factor. Record measured
			packing factors from test fits in the team standards.

			## Fin flutter (screen)
			NACA TN 4197 (Martin 1958): Vf = a sqrt(G / (K AR^3 (lambda+1) P / (2 (AR+2) (t/c)^3))), K = 39.3/14.696 = 2.674
			with G and P in the same units; AR = span^2 / area, lambda = tip/root, t/c = thickness / root chord. Apogee Peak of
			Flight #291/#411 printed K = 1.337, which overestimates Vf by sqrt 2 (corrected in #615). Evaluated at every point of
			the simulated flight with the local pressure and speed of sound. Solid isotropic plate only; composite layups need an
			effective G. Required margin (flutter speed / airspeed) is a team standard (structures.flutterMinMargin).

			## Ballast
			Static first guess: m = M (x_cg - x_t) / (x_t - x_b), x_t = x_cp - target * d, shifted by the difference between
			the static margin and the simulated minimum; then secant iterations on the simulated minimum ascent stability.
			A section whose mass (and CG) is overridden for its subcomponents gets the ballast added to the override.

			## Winds aloft
			OpenRocket multi-level wind model: speed, direction and turbulence (sd) per altitude, interpolated between levels.
			Power-law profile: v(h) = v_ground (h / 10 m)^alpha above 10 m (alpha ~ 1/7 open terrain). Overriding the wind
			speed / direction on a profile scales / rotates every level from the lowest one.

			## Aerodynamics
			OpenRocket Barrowman model at zero angle of attack, Reynolds number from sea-level ISA at each Mach; CD = friction +
			pressure + base. Margin = (CP - CG) / max body diameter.

			## Structural loads
			Axial (compression +): N(x) = m_fwd / m (T - D) + D_fwd. Bending: M(x) = sum_fwd N_i (x - x_i) - sum_fwd m_j a_j (x - x_j)
			with N_i = q A CNa_i alpha at each component CP, a_j = a_n + thetaDD (x_cg - x_j) (rigid-body inertial relief).
			Thin-wall stress sigma = N / (2 pi r t) + M / (pi r^2 t). Gust case: alpha = atan(v_gust / V) at max q.

			## Power budget
			Charge needed = average draw x (pad wait + simulated flight + search time), against rated capacity x derating
			(0.8 by default). E-match current I = V / (R_battery + R_match + R_wiring), wanted >= all-fire current x margin
			(2 by default); voltage while firing V - I R_battery against the electronics' brownout voltage.

			## Radio link
			Budget = transmit power + antenna gains - losses - receiver sensitivity. In the air, free-space loss
			20 log10(4 pi d f / c) to the farthest point of each flight branch; on the ground, plane-earth loss
			40 log10 d - 20 log10 h1 - 20 log10 h2 beyond the two-ray crossover 4 pi h1 h2 / lambda. Keep a 10 dB margin for
			antenna nulls, terrain and the airframe.

			## Physically possible inputs and flights
			Every argument is checked against a physical range (no negative mass or Cd, wind 0-150 m/s, launch angle 0-60 deg,
			site temperature -100 to +100 C). A flight OpenRocket stops (no lift-off, tumbling or a parachute opening under
			thrust), or one beyond Mach 3 or 150 G, is reported under PROBLEMS before its numbers.

			## Monte Carlo
			Randomized: wind speed/direction, launch angle/direction, turbulence seed; optionally structure mass (each
			component scaled, motors excluded), airframe drag (simulation listener scaling CD), motor thrust (listener scaling
			thrust, same burn time) and each parachute's Cd. Drivers = Pearson correlation of each input with the outputs.
			""";

	static void register(McpServer s, Context ctx) {
		s.resource(new Resource("openrocket://standards", "Team standards", "Active team standards (JSON).",
				"application/json", () -> ctx.standards().json()));
		s.resource(new Resource("openrocket://rules", "Competition rule set", "Active rule-set thresholds with references (JSON).",
				"application/json", () -> ctx.standards().rulesJson()));
		s.resource(new Resource("openrocket://methods", "Calculation methods", "Equations, constants and sources used by the tools.",
				"text/markdown", () -> METHODS.replace("{openRocketVersion}", Versions.openRocket())));

		s.prompt(new Prompt("what_can_you_do", "Show what this rocketry assistant can do for our project.",
				List.of(new PromptArg("design", "Path to the team's .ork file (optional).", false)),
				args -> """
						We are a student rocketry team and have not used this assistant before. %s
						Call capabilities, and design_status if a design is open. Then introduce, in a few short lines each, the
						five or six things that would help us most right now, worded as we would ask them, starting with
						where our design stands. Mention that units can be typed as we speak ("20 ft/s", "4 in"), that
						every change can be undone, and that pictures (cut-aways, 3-D views, charts) come back in the chat.
						End by asking what we are working on.
						""".formatted(args.getOrDefault("design", "").isBlank() ? ""
						: "Open our design " + args.get("design") + " first.")));

		s.prompt(new Prompt("meeting_followup", "Check a meeting's notes against the design and draft the follow-up.",
				List.of(new PromptArg("notes", "Where the notes are (a Notion page, a Slack thread, a Drive doc) or the notes themselves.", true),
						new PromptArg("design", "Path to the team's .ork file (or an open designId).", true)),
				args -> """
						Follow up our meeting. Notes: %s. Design: %s.
						1. Read the notes (through the Notion, Slack or Drive connector if they are a link). List the decisions,
						   the action items (task, owner, due date) and the open questions, and every number or design choice quoted.
						2. open_design (unless open), then verify_claims with every number and choice, each with its source.
						3. For each mismatch, say which side is likely out of date. For new decisions, check them with the right tool
						   (fin_flutter, compare_materials, rank_motors, parachute_sizes, check_requirements) and propose the edit;
						   do not change the design until I say so.
						4. Draft the follow-up: decisions, action items with owners and dates, mismatches to resolve, risks (rule
						   failures first). Do not post it anywhere until I say so; never invent owners or dates.
						""".formatted(args.get("notes"), args.get("design"))));

		s.prompt(new Prompt("weekly_update", "Weekly engineering update: what changed, what is open, what is next.",
				List.of(new PromptArg("design", "Path to the team's .ork file (or an open designId).", true),
						new PromptArg("sources", "Channels / pages to read, e.g. \"#avionics, #structures and the Meeting notes database\".", false),
						new PromptArg("since", "Compare with: \"last\" (default), a milestone label such as \"PDR\", or a date.", false)),
				args -> """
						Prepare this week's engineering update for %s.
						1. progress_log (mode record, note "weekly update"%s) and use its digest.
						2. %s
						3. Add action items done since the last update, overdue and due next, from our tracker; open risks (rule
						   failures first, then mismatches between notes and design, then schedule).
						4. Write the update short enough to read in a channel. Show it to me before posting it anywhere.
						""".formatted(args.get("design"),
						args.getOrDefault("since", "").isBlank() ? "" : ", since \"" + args.get("since") + "\"",
						args.getOrDefault("sources", "").isBlank()
								? "Read the team's channels and meeting notes from the last week through the connectors you have, and run verify_claims on the numbers they quote."
								: "Read " + args.get("sources") + " from the last week, and run verify_claims on the numbers they quote.")));

		s.prompt(new Prompt("recovery_review", "Walk through the full recovery chain for a design and write it up.",
				List.of(new PromptArg("design", "Path to the .ork file (or an open designId).", true),
						new PromptArg("pinType", "Shear pin type from the team standards, e.g. \"4-40 nylon\".", false)),
				args -> """
						Review the recovery system of %s.
						1. open_design (unless it is already open), then get_design and get_standards.
						2. check_requirements to see the rule status of descent rates and deployment altitudes.
						3. recovery_analysis%s to get deployment airspeed, opening loads and pins for every device in every stage.
						4. For the drogue (or first event), run deployment_delay_sweep with delays 0-4 s to find the latest safe deployment.
						5. For each bay: ejection_charge (pins to break) and recovery_bay_fit (parachute + cord volumes).
						6. If a descent rate is out of range, size_parachute and propose a real parachute from the parts database.
						Write the result like a team test report: inputs, equations, values, results, and which numbers are
						simulated vs. calculated vs. assumed. List open risks and required ground tests.
						""".formatted(args.get("design"), args.getOrDefault("pinType", "").isBlank() ? ""
						: " with pinType \"" + args.get("pinType") + "\"")));

		s.prompt(new Prompt("design_review", "Launch Canada style design review of a vehicle.",
				List.of(new PromptArg("design", "Path to the .ork file (or an open designId).", true)),
				args -> """
						Prepare a design review for %s against the active rule set (get_standards).
						- get_design: configuration, masses, CG/CP and stability per stage stack.
						- check_requirements (includes the maximum-wind case). Explain every FAIL/WARN and propose fixes.
						- get_flight_data for stability vs time: one series up to rail departure and one for the full ascent (DTEG R10.3.2).
						- For staged vehicles: sustainer stability after separation, tilt and altitude at ignition, inhibit altitude.
						- recovery_analysis for every stage; recovery_sections for landing energy per section and bay fill.
						- structural_loads for the joints (couplers, fasteners).
						- fin_flutter for every fin set (margin along the flight); ballast if the stability floor is not met.
						- monte_carlo with vehicle uncertainty (massSd, dragSd, thrustSd, chuteCdSd) for the landing area and drivers.
						- If there is an earlier version (another .ork, or a git tag / commit from the last review), compare_designs
						  for what changed since then.
						Summarize as a table of requirement, value, status and reference, then the action list.
						""".formatted(args.get("design"))));

		s.prompt(new Prompt("motor_selection", "Choose a motor for a goal.",
				List.of(new PromptArg("design", "Path to the .ork file (or an open designId).", true),
						new PromptArg("goal", "e.g. \"10,000 ft apogee\" or \"max altitude on an L2 motor\".", true),
						new PromptArg("certLevel", "L1, L2 or L3.", false)),
				args -> """
						Find a motor for %s with the goal: %s%s.
						Open the design, identify the motor mount with get_design, then rank_motors with the right objective and
						filters. For the top candidates, set_motor on a new flight configuration and run check_requirements (rail exit,
						thrust-to-weight, stability, Mach). Recommend one, with the ejection delay (optimum delay) and trade-offs.
						""".formatted(args.get("design"), args.get("goal"),
						args.getOrDefault("certLevel", "").isBlank() ? "" : " (certification " + args.get("certLevel") + ")")));
	}
}
