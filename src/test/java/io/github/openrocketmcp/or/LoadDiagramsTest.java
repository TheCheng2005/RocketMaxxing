package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The UTAT load calculation as the app does it, against the team's own script (Load_calculations.py, version 4.0, run
 * unchanged on its Discovery inputs in src/test/resources/utat): the same stations, loads, shear, bending moment and
 * axial force.
 */
class LoadDiagramsTest {
	static final Path DIR = Path.of("src/test/resources/utat");

	// The script's output (plotly figure data), Discovery, alpha 5 degrees.
	static final double[] MX = { 0.0, 0.34575, 0.451, 0.727688597, 0.7305, 1.01, 1.175, 1.34, 1.96, 2.58, 2.7285, 2.877, 3.197, 3.517, 3.7867,
			4.0564, 4.197298199, 4.215542133, 4.2332, 4.41 };
	static final String[] AT = { "Nose tip", "Nosecone", "Nose/Rec Bay Joint", "Nose/body lift", "Recovery Bay", "Rec Bay/UEB Joint",
			"Upper Engineering Bay", "UEB/Fuel Tank Joint", "Fuel Tank", "Fuel Tank/LEB Joint", "Lower Engineering Bay", "LEB/Ox Tank Joint",
			"Oxidizer Tank", "Ox Tank/Injector Joint", "Injector Bay", "Injector/Fin Can Joint", "Body-wing lift", "Wing lift", "Fin Can", "Aft end" };
	static final double[] LOAD = { 0.0, 1.084782033, 0.0, 142.405658432, -11.865821065, 0.0, -28.695810104, 0.0, -70.829488922, 0.0,
			-42.072871003, 0.0, -119.811602693, 0.0, -186.91330728, 0.0, 167.370453407, 430.763762133, -281.43575494, 0.0 };
	static final double[] SHEAR = { 0.0, 1.084782033, 1.084782033, 143.490440465, 131.624619401, 131.624619401, 102.928809297, 102.928809297,
			32.099320375, 32.099320375, -9.973550628, -9.973550628, -129.785153321, -129.785153321, -316.698460601, -316.698460601,
			-149.328007194, 281.43575494, 0.0 };
	static final double[] MOMENT = { 0.0, 44.205190428, 44.319363737, 44.619510556, 45.022919995, 81.812001118, 103.530063319, 120.513316853,
			184.329178618, 204.23075725, 208.997506326, 207.516434058, 204.324897857, 162.793648794, 127.790592943, 42.377018119, -2.245224728,
			-4.96955503, 0.0, 0.0 };
	// Axial force at the parts and joints (the script's axial diagram).
	static final double[] AXIAL_X = { 0.34575, 0.451, 0.7305, 1.01, 1.175, 1.34, 1.96, 2.58, 2.7285, 2.877, 3.197, 3.517, 3.7867, 4.0564, 4.2332 };
	static final double[] AXIAL = { -238.823597843, -238.823597843, -211.837781119, -211.837781119, -183.320479688, -183.320479688,
			-148.02591835, -148.02591835, -133.937432602, -133.937432602, -100.502950391, -100.502950391, -57.38666831, -57.38666831, 0.0 };

	static final List<LoadDiagrams.Joint> JOINTS = List.of(new LoadDiagrams.Joint("Nose/Rec Bay Joint", 0.4510),
			new LoadDiagrams.Joint("Rec Bay/UEB Joint", 1.0100), new LoadDiagrams.Joint("UEB/Fuel Tank Joint", 1.3400),
			new LoadDiagrams.Joint("Fuel Tank/LEB Joint", 2.5800), new LoadDiagrams.Joint("LEB/Ox Tank Joint", 2.8770),
			new LoadDiagrams.Joint("Ox Tank/Injector Joint", 3.5170), new LoadDiagrams.Joint("Injector/Fin Can Joint", 4.0564));

	@Test
	@SuppressWarnings("unchecked")
	void theTeamsScriptOnItsOwnInputs() throws Exception {
		LoadDiagrams.Input in = LoadDiagrams.fromFiles(DIR.resolve("Discovery_part_info.dat"), DIR.resolve("Open_rocket_info.dat"),
				DIR.resolve("Discovery_Centres_of_Pressure.DAT"), DIR.resolve("Discovery_Lift_Coefficient_Curve_Slopes.DAT"),
				DIR.resolve("Discovery_Cd.dat"), 0.0872665, JOINTS);
		Map<String, Object> out = LoadDiagrams.compute(in);
		assertEquals(260.22330943026753, (double) out.get("drag"), 1e-9, "the script's printed total drag");
		List<Map<String, Object>> st = (List<Map<String, Object>>) out.get("stations");
		assertEquals(MX.length, st.size());
		for (int i = 0; i < MX.length; i++) {
			Map<String, Object> r = st.get(i);
			String at = AT[i] + " at " + MX[i];
			assertEquals(MX[i], (double) r.get("x"), 1e-8, at);
			assertEquals(AT[i], r.get("at"), at);
			assertEquals(LOAD[i], (double) r.get("load"), 1e-6, "load " + at);
			assertEquals(MOMENT[i], (double) r.get("moment"), 1e-6, "moment " + at);
			if (i < SHEAR.length) {
				assertEquals(SHEAR[i], (double) r.get("shear"), 1e-6, "shear " + at);
			}
		}
		for (int k = 0; k < AXIAL_X.length; k++) {
			double x = AXIAL_X[k];
			Map<String, Object> r = st.stream().filter(m -> Math.abs((double) m.get("x") - x) < 1e-8).findFirst().orElseThrow();
			assertEquals(AXIAL[k], (double) r.get("axial"), 1e-6, "axial at " + x);
		}
		assertEquals(JOINTS.size(), ((List<?>) out.get("joints")).size());
		assertEquals(208.997506326, ((double[]) out.get("maxMoment"))[0], 1e-6);
	}

	@Test
	void aWrongFileSaysWhatIsWrong(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
		Path parts = tmp.resolve("parts.dat");
		java.nio.file.Files.writeString(parts, "Length,Reference Area,Rocket_Part,mass_m,x_cm\n441,16.5,Nosecone\n");
		var e = org.junit.jupiter.api.Assertions.assertThrows(io.github.openrocketmcp.mcp.ToolException.class,
				() -> LoadDiagrams.fromFiles(parts, DIR.resolve("Open_rocket_info.dat"), DIR.resolve("Discovery_Centres_of_Pressure.DAT"),
						DIR.resolve("Discovery_Lift_Coefficient_Curve_Slopes.DAT"), DIR.resolve("Discovery_Cd.dat"), 0.0872665, JOINTS));
		assertTrue(e.getMessage().contains("5 columns"), e.getMessage());
		var e2 = org.junit.jupiter.api.Assertions.assertThrows(io.github.openrocketmcp.mcp.ToolException.class,
				() -> LoadDiagrams.fromFiles(DIR.resolve("Discovery_part_info.dat"), DIR.resolve("Open_rocket_info.dat"), DIR.resolve("Discovery_Cd.dat"),
						DIR.resolve("Discovery_Lift_Coefficient_Curve_Slopes.DAT"), DIR.resolve("Discovery_Cd.dat"), 0.0872665, JOINTS));
		assertTrue(e2.getMessage().contains("centres of pressure"), "a CD table given as the CP table: " + e2.getMessage());
	}

	@Test
	@SuppressWarnings("unchecked")
	void fromADesignTheDiagramsClose() throws Exception {
		OrRuntime.init();
		Designs ds = new Designs();
		Designs.Design d = ds.openExample("Dual parachute");
		var sim = d.doc.getSimulations().get(0);
		Sims.ensure(sim);
		LoadDiagrams.Input in = LoadDiagrams.fromSimulation(sim, Math.toRadians(5), false);
		Map<String, Object> out = LoadDiagrams.compute(in);
		String[] svg = LoadDiagrams.svgs(out, in, "Test");
		assertTrue(svg[0].startsWith("<svg") && svg[0].contains("bending moment") && svg[0].contains("<path"), "moment plot");
		assertTrue(svg[1].contains("shear") && svg[1].contains("<path"), "shear plot");
		List<Map<String, Object>> st = (List<Map<String, Object>>) out.get("stations");
		Map<String, Object> last = st.get(st.size() - 1);
		double lift = (double) out.get("lift"), maxM = Math.abs(((double[]) out.get("maxMoment"))[0]);
		assertTrue(lift > 0 && maxM > 0, out.toString());
		// Rigid body in balance: shear and bending moment come back to zero at the aft end.
		assertEquals(0, (double) last.get("shear"), 1e-6 * lift, "shear closes");
		assertEquals(0, (double) last.get("moment"), 1e-6 * maxM, "moment closes");
		assertTrue(!((List<?>) out.get("joints")).isEmpty(), "the design's joints are reported");
		ds.close(d.id);
	}
}
