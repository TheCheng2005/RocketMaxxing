package io.github.openrocketmcp.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.Context;

/** Copies of unsaved work: they never change the design, the newest are kept, and they open back as designs. */
class BackupsTest {
	@Test
	void aCopyLeavesTheDesignAsItWas(@TempDir Path ws) throws Exception {
		Context ctx = new Context(Standards.defaults(), ws, false);
		Designs.Design d = ctx.designs.openExample("A simple model rocket");
		Path own = ctx.designs.save(d, ws.resolve("rocket.ork"));
		d.doc.getRocket().setName("Edited / renamed: rocket?");
		d.doc.setSaved(false);

		Path copy = Backups.write(ctx, d);
		assertTrue(copy.startsWith(ws.resolve(Backups.FOLDER)), copy.toString());
		assertTrue(copy.getFileName().toString().matches("Edited renamed rocket \\d{4}-\\d{2}-\\d{2} \\d{2}-\\d{2}-\\d{2}\\.ork"),
				"a file name any system accepts: " + copy.getFileName());
		assertEquals(own, d.path, "the design is still tied to its own file");
		assertFalse(d.doc.isSaved(), "and still has unsaved changes");
		assertTrue(Files.size(copy) > 0);
		Designs.Design back = ctx.designs.open(copy);
		assertEquals("Edited / renamed: rocket?", back.name(), "the copy reads back as the design");
		try (Stream<Path> s = Files.list(ws.resolve(Backups.FOLDER))) {
			assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "no half-written files left");
		}
	}

	@Test
	void keepsTheNewestOfEachDesign(@TempDir Path ws) throws Exception {
		Path dir = Files.createDirectories(ws.resolve(Backups.FOLDER));
		for (int i = 0; i < Backups.KEEP + 4; i++) {
			Files.writeString(dir.resolve(String.format("Rocket 2026-01-%02d 10-00-00.ork", i + 1)), "x");
		}
		Files.writeString(dir.resolve("Other 2026-01-01 10-00-00.ork"), "x");
		Backups.prune(dir, "Rocket");
		try (Stream<Path> s = Files.list(dir)) {
			var left = s.map(p -> p.getFileName().toString()).sorted().toList();
			assertEquals(Backups.KEEP + 1, left.size(), left.toString());
			assertTrue(left.contains("Other 2026-01-01 10-00-00.ork"), "another design's copies are not touched");
			assertFalse(left.contains("Rocket 2026-01-01 10-00-00.ork"), "the oldest went");
			assertTrue(left.contains(String.format("Rocket 2026-01-%02d 10-00-00.ork", Backups.KEEP + 4)), "the newest stayed");
		}
	}

	@Test
	void minutesFromThePreferences(@TempDir Path home) {
		UiPrefs prefs = new UiPrefs(home.resolve("ui-prefs.json"));
		assertEquals(Backups.DEFAULT_MINUTES, Backups.minutes(prefs));
		prefs.set(com.google.gson.JsonParser.parseString("{\"" + Backups.PREF + "\": \"0\"}").getAsJsonObject());
		assertEquals(0, Backups.minutes(prefs), "off");
		prefs.set(com.google.gson.JsonParser.parseString("{\"" + Backups.PREF + "\": \"soon\"}").getAsJsonObject());
		assertEquals(Backups.DEFAULT_MINUTES, Backups.minutes(prefs), "a value that cannot be read: the default");
	}
}
