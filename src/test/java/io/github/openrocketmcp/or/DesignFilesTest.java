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
}
