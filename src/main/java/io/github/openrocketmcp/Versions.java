package io.github.openrocketmcp;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import info.openrocket.core.util.BuildProperties;

/**
 * The versions of what is running. The engine's and the app's are set in gradle.properties and put into
 * openrocketmcp/version.properties by the build; OpenRocket's is the one its own library reports, so text that
 * names it follows an upgrade by itself.
 */
public final class Versions {
	/** The engine and MCP server (openrocket-mcp). */
	public static final String ENGINE;
	/** RocketMaxxing, the desktop app. */
	public static final String APP;

	static {
		Properties p = new Properties();
		try (InputStream in = Versions.class.getResourceAsStream("/openrocketmcp/version.properties")) {
			if (in != null) {
				p.load(in);
			}
		} catch (IOException e) {
			// reported as "dev" below
		}
		ENGINE = built(p.getProperty("engine"));
		APP = built(p.getProperty("app"));
	}

	private Versions() {
	}

	/** A value the build filled in; "dev" when the classes run without the build's resources (an IDE's own compile). */
	private static String built(String v) {
		return v == null || v.isBlank() || v.contains("${") ? "dev" : v.trim();
	}

	/** The OpenRocket library's version, e.g. "24.12". */
	public static String openRocket() {
		try {
			String v = BuildProperties.getVersion();
			return v == null || v.isBlank() ? "(unknown version)" : v;
		} catch (RuntimeException | LinkageError e) {
			return "(unknown version)";
		}
	}
}
