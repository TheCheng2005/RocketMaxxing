package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import info.openrocket.core.file.GeneralRocketSaver;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.RocketComponent;

/** A design made where the team's own motor was imported into OpenRocket: the motor goes back once its file is. */
class MissingMotorsTest {

	@Test
	void aMotorTheDatabaseLacksGoesBackWhereTheFileHadItOnceImported(@TempDir Path dir) throws Exception {
		OrRuntime.init();
		Designs ds = new Designs();
		Designs.Design d = ds.openExample("Dual parachute");
		FlightConfiguration fc = d.doc.getRocket().getSelectedConfiguration();
		MotorMount mount = null;
		for (RocketComponent c : d.doc.getRocket()) {
			if (c instanceof MotorMount mm && mm.isMotorMount() && mm.getMotorConfig(fc.getId()).getMotor() != null) {
				mount = mm;
			}
		}
		assertNotNull(mount, "the example flies a motor");
		MotorConfiguration mc = mount.getMotorConfig(fc.getId());
		String designation = ((ThrustCurveMotor) mc.getMotor()).getDesignation();
		String digest = ((ThrustCurveMotor) mc.getMotor()).getDigest();

		// Saved, then the motor renamed to one this database has never seen (as a team motor on another computer).
		Path saved = dir.resolve("saved.ork");
		new GeneralRocketSaver().save(saved.toFile(), d.doc);
		String xml = new String(MissingMotors.xml(saved), StandardCharsets.UTF_8)
				.replace("<designation>" + designation + "</designation>", "<designation>TeamOnly-T42</designation>")
				.replace("<digest>" + digest + "</digest>", "<digest>0000feedbeef0000</digest>");
		Path file = dir.resolve("team.ork");
		ByteArrayOutputStream zipped = new ByteArrayOutputStream();
		try (ZipOutputStream z = new ZipOutputStream(zipped)) {
			z.putNextEntry(new ZipEntry("rocket.ork"));
			z.write(xml.getBytes(StandardCharsets.UTF_8));
		}
		Files.write(file, zipped.toByteArray());

		Designs.Design opened = ds.open(file);
		assertEquals(1, opened.missingMotors.size(), "the missing motor is known: " + opened.loadWarnings);
		MissingMotors.Use u = opened.missingMotors.get(0);
		assertEquals("TeamOnly-T42", u.designation());
		assertEquals(mount.getID().toString(), u.mountId());
		assertTrue(opened.loadWarnings.stream().anyMatch(w -> w.contains("TeamOnly-T42") && w.contains("Import its .rse")), opened.loadWarnings.toString());
		MotorMount there = (MotorMount) MissingMotors.mount(opened.doc.getRocket(), u);
		FlightConfiguration ofc = opened.doc.getRocket().getSelectedConfiguration();
		assertNull(there.getMotorConfig(ofc.getId()).getMotor(), "OpenRocket left the mount empty");

		// The team's motor file is imported: the motor is back in the same mount and configuration, the design unchanged.
		Path rse = dir.resolve("team.rse");
		Motors.createCustom(new Motors.CustomMotor(u.manufacturer(), "TeamOnly-T42", "solid", 0.054, 0.4, 1.4, 0.7,
				new double[] { 0, 0.05, 1.8, 2.0 }, new double[] { 0, 900, 700, 0 }, Double.NaN, Double.NaN, new double[] { 6 }, null), rse);
		assertNotNull(MissingMotors.find(u), "the imported motor is found by its maker and name");
		List<String> back = ds.restoreMissingMotors();
		assertEquals(1, back.size(), back.toString());
		assertNotNull(there.getMotorConfig(ofc.getId()).getMotor());
		assertEquals("TeamOnly-T42", ((ThrustCurveMotor) there.getMotorConfig(ofc.getId()).getMotor()).getDesignation());
		assertTrue(opened.missingMotors.isEmpty());
		assertTrue(opened.doc.isSaved(), "putting back what the file had is not an edit");
		assertFalse(opened.loadWarnings.stream().anyMatch(w -> w.contains("TeamOnly-T42") && w.contains("Import its")),
				"the warning goes with it: " + opened.loadWarnings);
		ds.close(opened.id);
		ds.close(d.id);
	}
}
