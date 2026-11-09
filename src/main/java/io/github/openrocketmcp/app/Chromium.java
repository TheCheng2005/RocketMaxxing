package io.github.openrocketmcp.app;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefBuildInfo;
import me.friwi.jcefmaven.EnumPlatform;

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
		check(dir);
	}

	/**
	 * The unpacked Chromium of the installation (next to its lib folder, or next to the jars in an installer's app
	 * folder). Throws IllegalStateException, saying what is wrong, when there is none this program can use.
	 */
	static Path find() {
		Path jar;
		try {
			jar = Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (Exception e) {
			throw new IllegalStateException("Cannot tell where the program is installed: " + e.getMessage());
		}
		for (Path dir : List.of(jar.getParent().getParent().resolve("chromium"), jar.getParent().resolve("chromium"))) {
			if (Files.isDirectory(dir)) {
				return check(dir.toAbsolutePath().normalize());
			}
		}
		throw new IllegalStateException("There is no Chromium next to the program (from source: ./gradlew rocketmaxxing).");
	}

	/**
	 * macOS: loads JCEF's library now, before the app's first event. Loading it adds a mouse-event watcher before its
	 * own data is set up, so a mouse event in between (while the library loads beside a running app) crashed the app.
	 * Call it before anything starts AWT.
	 */
	static void preload(Path dir) {
		if (MacDesktop.isMac()) {
			System.load(dir.resolve("libjcef.dylib").toString());
		}
	}

	/**
	 * {@code dir}, when it holds a complete Chromium for this platform and this program's JCEF. Throws
	 * IllegalStateException, saying what is wrong, when it does not.
	 */
	static Path check(Path dir) {
		File meta = dir.resolve("build_meta.json").toFile();
		if (!meta.isFile() || !Files.isRegularFile(dir.resolve("install.lock"))) { // install.lock: unpacked completely
			throw new IllegalStateException("The Chromium in " + dir + " is incomplete.");
		}
		CefBuildInfo have;
		String want;
		String platform;
		try {
			have = CefBuildInfo.fromFile(meta);
			want = CefBuildInfo.fromClasspath().getReleaseTag();
			platform = EnumPlatform.getCurrentPlatform().getIdentifier();
		} catch (Exception e) {
			throw new IllegalStateException("Cannot use the Chromium in " + dir + ": " + e);
		}
		if (!have.getReleaseTag().equals(want) || !have.getPlatform().equals(platform)) {
			throw new IllegalStateException("The Chromium in " + dir + " is " + have.getReleaseTag() + " for "
					+ have.getPlatform() + "; this program needs " + want + " for " + platform + ".");
		}
		return dir;
	}
}
