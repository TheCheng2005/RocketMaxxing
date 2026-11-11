package io.github.openrocketmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import io.github.openrocketmcp.app.App;

class VersionsTest {
	@Test
	void theBuildFillsInTheVersions() {
		assertTrue(Versions.ENGINE.matches("\\d+\\.\\d+\\.\\d+.*"), "engine version from gradle.properties: " + Versions.ENGINE);
		assertTrue(Versions.APP.matches("\\d+\\.\\d+\\.\\d+.*"), "app version from gradle.properties: " + Versions.APP);
		assertEquals(Versions.ENGINE, Main.VERSION);
		assertEquals(Versions.APP, App.VERSION);
		assertTrue(Versions.openRocket().matches("\\d+\\.\\d+.*"), "OpenRocket says its own version: " + Versions.openRocket());
	}

	/** The interface's package carries the app's version too: the two are changed together for a release. */
	@Test
	void theInterfacePackageHasTheAppVersion() throws Exception {
		Path pkg = Path.of("app", "package.json");
		assertTrue(Files.isRegularFile(pkg), "tests run from the project folder");
		String v = JsonParser.parseString(Files.readString(pkg, StandardCharsets.UTF_8)).getAsJsonObject().get("version").getAsString();
		assertEquals(Versions.APP, v, "app/package.json's version and appVersion in gradle.properties");
	}
}
