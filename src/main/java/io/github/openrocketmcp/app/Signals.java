package io.github.openrocketmcp.app;

import java.util.List;
import java.util.Locale;

import sun.misc.Signal;

/**
 * The one place that uses sun.misc.Signal, which Java offers no public replacement for (the compiler warns that it
 * may go one day). Kept apart so that, on a Java without it, only this is lost and the window still works.
 */
final class Signals {
	private Signals() {
	}

	/**
	 * Chromium, once started, handles SIGTERM, SIGINT and SIGHUP itself (as a Quit, which asks about unsaved designs, or
	 * on Linux only closes the page). Java gets them back, so stopping the app (Ctrl+C, kill) stops it as it did with a
	 * browser window: right away, and its shutdown hooks free the designs folder.
	 */
	static void giveBackToJava() {
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			return; // no such handlers there
		}
		try {
			for (String name : List.of("TERM", "INT", "HUP")) {
				try {
					Signal.handle(new Signal(name), s -> System.exit(128 + s.getNumber()));
				} catch (IllegalArgumentException | UnsupportedOperationException e) {
					// the JVM keeps this signal (-Xrs)
				}
			}
		} catch (LinkageError e) {
			// a Java runtime without sun.misc.Signal (the jdk.unsupported module): Chromium keeps the signals
		}
	}
}
