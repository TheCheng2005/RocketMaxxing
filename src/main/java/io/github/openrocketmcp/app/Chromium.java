package io.github.openrocketmcp.app;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefBuildInfo;
import me.friwi.jcefmaven.EnumPlatform;

import io.github.openrocketmcp.mcp.Log;

/**
 * The Chromium that RocketMaxxing's window runs on. It is unpacked when the app is built (the build runs {@link #main}
 * with jcefmaven's installer) and found next to the program when the app starts, like the interface. The app never
 * downloads or unpacks it itself, so the window works offline and from a folder the app cannot write to.
 */
final class Chromium {
	private Chromium() {
	}

	/** Build step: unpacks the Chromium on the classpath (the jcef-natives jar for this platform) into the folder given. */
	public static void main(String[] args) throws Exception {
		Path dir = Path.of(args[0]).toAbsolutePath().normalize();
		CefAppBuilder b = new CefAppBuilder();
		b.setInstallDir(dir.toFile());
		b.setMirrors(List.of()); // the bundle comes from the build's dependencies, never from a download
		b.setProgressHandler((step, done) -> {
		});
		b.install();
		if (check(dir) == null) {
			throw new IllegalStateException("Chromium was not unpacked into " + dir);
		}
	}

	/**
	 * The unpacked Chromium of the installation (next to its lib folder, or next to the jars in an installer's app
	 * folder), if it is the one this program was built with; null otherwise, with the reason in the log.
	 */
	static Path find() {
		try {
			Path jar = Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			for (Path dir : List.of(jar.getParent().getParent().resolve("chromium"), jar.getParent().resolve("chromium"))) {
				if (Files.isDirectory(dir)) {
					return check(dir.toAbsolutePath().normalize());
				}
			}
		} catch (Exception ignored) {
			// not run from an installation
		}
		Log.info("No Chromium next to the program (build it with ./gradlew rocketmaxxing).");
		return null;
	}

	/** {@code dir} if it holds a complete Chromium for this platform and this program's JCEF; else null. */
	static Path check(Path dir) {
		try {
			File meta = dir.resolve("build_meta.json").toFile();
			if (!meta.isFile() || !Files.isRegularFile(dir.resolve("install.lock"))) { // install.lock: unpacked completely
				Log.info("The Chromium in " + dir + " is incomplete.");
				return null;
			}
			CefBuildInfo have = CefBuildInfo.fromFile(meta);
			String want = CefBuildInfo.fromClasspath().getReleaseTag();
			String platform = EnumPlatform.getCurrentPlatform().getIdentifier();
			if (!have.getReleaseTag().equals(want) || !have.getPlatform().equals(platform)) {
				Log.info("The Chromium in " + dir + " is " + have.getReleaseTag() + " for " + have.getPlatform()
						+ "; this program needs " + want + " for " + platform + ".");
				return null;
			}
			return dir;
		} catch (Exception | LinkageError e) {
			Log.info("Cannot use the Chromium in " + dir + ": " + e);
			return null;
		}
	}
}
