package io.github.openrocketmcp.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where the flight video is saved, and under what name. */
class DownloadsTest {
	@Test
	void downloadsThenDesktopThenTheDesignsFolder(@TempDir Path home, @TempDir Path designs) throws Exception {
		assertEquals(designs, Downloads.folder(home, designs), "neither folder: the designs folder");
		Files.createDirectories(home.resolve("Desktop"));
		assertEquals(home.resolve("Desktop"), Downloads.folder(home, designs));
		Files.createDirectories(home.resolve("Downloads"));
		assertEquals(home.resolve("Downloads"), Downloads.folder(home, designs));
		// Linux: the folder user-dirs.dirs names (here a translated one).
		Files.createDirectories(home.resolve("Telechargements"));
		Files.createDirectories(home.resolve(".config"));
		Files.writeString(home.resolve(".config/user-dirs.dirs"), "# comment\nXDG_DOWNLOAD_DIR=\"$HOME/Telechargements\"\n");
		assertEquals(home.resolve("Telechargements"), Downloads.folder(home, designs));
	}

	@Test
	void namesAreSafeAndReplaceNothing(@TempDir Path dir) throws Exception {
		assertEquals(dir.resolve("Maple 10K flight.mp4"), Downloads.freePath(dir, "Maple 10K flight.mp4"));
		assertEquals(dir.resolve("a b c flight.mp4"), Downloads.freePath(dir, "a/b:c* flight.mp4"), "no folders, nothing Windows refuses");
		assertEquals(dir.resolve("download"), Downloads.freePath(dir, " .. "));
		Files.writeString(dir.resolve("Maple 10K flight.mp4"), "x");
		assertEquals(dir.resolve("Maple 10K flight (2).mp4"), Downloads.freePath(dir, "Maple 10K flight.mp4"));
		Files.writeString(dir.resolve("Maple 10K flight (2)-keyframes.png"), "x");
		assertEquals(dir.resolve("Maple 10K flight (3).mp4"), Downloads.freePath(dir, "Maple 10K flight.mp4"),
				"the stills beside a video count too");
	}

	@Test
	void onlyMediaAndDocumentsOpen() {
		assertTrue(Downloads.openable(Path.of("x/flight.MP4")));
		assertTrue(Downloads.openable(Path.of("report.pdf")));
		assertFalse(Downloads.openable(Path.of("setup.exe")));
		assertFalse(Downloads.openable(Path.of("run.sh")));
		assertFalse(Downloads.openable(Path.of("RocketMaxxing.app")));
	}

	@Test
	void aPlotIsSavedWithoutReplacingAnything(@TempDir Path dir) throws Exception {
		Path csv = Downloads.export(dir, "Maple flight plot.csv", "\"Time (s)\",\"Altitude (m)\"\n0,0\n", null);
		assertEquals(dir.resolve("Maple flight plot.csv"), csv);
		assertTrue(Files.readString(csv).startsWith("\"Time (s)\""));
		Path again = Downloads.export(dir, "Maple flight plot.csv", "x", null);
		assertEquals(dir.resolve("Maple flight plot (2).csv"), again, "the first one is kept");
		byte[] png = { (byte) 0x89, 'P', 'N', 'G' };
		Path picture = Downloads.export(dir, "a/b plot.png", null, java.util.Base64.getEncoder().encodeToString(png));
		assertEquals(dir.resolve("a b plot.png"), picture, "no folders in the name");
		assertEquals(4, Files.size(picture));
		for (String[] bad : new String[][] { { "plot.exe", "x", null }, { "plot.png", null, null }, { "plot.png", "x", "eA==" },
				{ "plot.png", null, "not base64!" }, { "plot.csv", "", null } }) {
			org.junit.jupiter.api.Assertions.assertThrows(io.github.openrocketmcp.mcp.ToolException.class,
					() -> Downloads.export(dir, bad[0], bad[1], bad[2]), bad[0]);
		}
	}
}
