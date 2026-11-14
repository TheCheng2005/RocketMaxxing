package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.openrocketmcp.mcp.ToolException;

/** A design file that cannot be opened or saved says what is wrong with it and what could have caused it. */
class DesignFilesTest {
	private static String refused(org.junit.jupiter.api.function.Executable e) {
		return assertThrows(ToolException.class, e).getMessage();
	}

	@Test
	void opening(@TempDir Path dir) throws Exception {
		Designs designs = new Designs();
		String missing = refused(() -> designs.open(dir.resolve("gone.ork")));
		assertTrue(missing.contains("File not found") && missing.contains("moved, renamed or deleted"), missing);

		String folder = refused(() -> designs.open(dir));
		assertTrue(folder.contains("is a folder, not a design"), folder);

		Files.writeString(dir.resolve("empty.ork"), "");
		String empty = refused(() -> designs.open(dir.resolve("empty.ork")));
		assertTrue(empty.contains("empty (0 bytes)") && empty.contains("did not finish"), empty);

		Files.writeString(dir.resolve("notes.ork"), "this is not a rocket");
		String broken = refused(() -> designs.open(dir.resolve("notes.ork")));
		assertTrue(broken.contains("could not read notes.ork") && broken.contains("damaged or incomplete") && !broken.contains(".."), broken);
	}

	@Test
	void saving(@TempDir Path dir) throws Exception {
		Designs designs = new Designs();
		Designs.Design d = designs.openExample("A simple model rocket");
		String wrong = refused(() -> designs.save(d, dir.resolve("rocket.pdf")));
		assertTrue(wrong.contains("must end in .ork") && wrong.contains("rocket.pdf"), wrong);
		String folder = refused(() -> designs.save(d, dir));
		assertTrue(folder.contains("is a folder"), folder);

		// A file in the way of the folder: the system refuses, and the message says what usually causes that.
		Files.writeString(dir.resolve("blocked"), "x");
		ToolException e = assertThrows(ToolException.class, () -> designs.save(d, dir.resolve("blocked").resolve("rocket.ork")));
		assertEquals(ToolException.Kind.SYSTEM, e.kind());
		assertTrue(e.getMessage().contains("Could not save") && e.getMessage().contains("read-only") && e.getMessage().contains("still open here"),
				e.getMessage());

		designs.save(d, dir.resolve("new folder").resolve("rocket.ork"));
		assertTrue(Files.size(dir.resolve("new folder").resolve("rocket.ork")) > 0, "a folder that is not there yet is made");
	}

	/** A copy for RockSim or RASAero is written beside the design, which stays tied to its own .ork, and reads back. */
	@Test
	void copiesForOtherPrograms(@TempDir Path dir) throws Exception {
		Designs designs = new Designs();
		Designs.Design d = designs.openExample("A simple model rocket");
		Path own = designs.save(d, dir.resolve("rocket.ork"));
		for (String name : new String[] { "rocket.rkt", "rocket.CDX1" }) {
			java.util.List<String> notes = new java.util.ArrayList<>();
			Path copy = designs.save(d, dir.resolve(name), notes);
			assertTrue(Files.size(copy) > 0, name);
			assertEquals(own, d.path, "the design keeps its .ork after writing " + name);
			Designs.Design back = designs.open(copy);
			assertTrue(back.doc.getRocket().getChildCount() > 0, name + " reads back as a rocket");
			assertEquals(copy, back.importedFrom, name + " is an import: it is never written over");
			String same = refused(() -> designs.save(back, copy));
			assertTrue(same.contains("imported from"), same);
		}
	}

	/** A file from another program opens with a list of what to look at; the design's own .ork opens with none. */
	@Test
	void importsSayWhatToCheck(@TempDir Path dir) throws Exception {
		Designs designs = new Designs();
		Designs.Design d = designs.openExample("A simple model rocket");
		Path own = designs.save(d, dir.resolve("rocket.ork"));
		assertEquals(null, designs.open(own).importReport, "an .ork that reads cleanly has nothing to report");

		Designs.Design rkt = designs.open(designs.save(d, dir.resolve("rocket.rkt")));
		assertEquals("RockSim", rkt.importReport.format());
		assertTrue(rkt.importReport.findings().stream().anyMatch(f -> f.level().equals("note") && f.text().contains("No motors came in")),
				rkt.importReport.findings().toString());
		assertTrue(rkt.importReport.findings().stream().anyMatch(f -> f.text().contains("No simulations came in")));

		Designs.Design ras = designs.open(designs.save(d, dir.resolve("rocket.CDX1")));
		assertEquals("RASAero II", ras.importReport.format());
		assertTrue(ras.importReport.findings().stream().anyMatch(f -> f.text().contains("outside of the rocket only")),
				ras.importReport.findings().toString());

		// OpenRocket's own warnings are shown as sentences, without Java class names.
		assertEquals("Body Tube cannot be attached to a Nose Cone: it was left out.",
				ImportCheck.plain("info.openrocket.core.rocketcomponent.BodyTube can not be attached to Nose Cone, ignoring component."));
		assertEquals("Trapezoidal Fin Set cannot be attached to an Inner Tube: it was left out.",
				ImportCheck.plain("Trapezoidal Fin Set can not be attached to Inner Tube, ignoring component."));
		assertTrue(ImportCheck.plain("Could not find motor 'A6Q' in the OpenRocket motors database. Please add it manually.")
				.contains("OpenRocket motors database. Import its file on the Motors page"));
	}
}
