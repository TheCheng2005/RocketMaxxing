package io.github.openrocketmcp.or;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.OpenRocketDocumentFactory;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.file.GeneralRocketLoader;
import info.openrocket.core.file.GeneralRocketSaver;
import info.openrocket.core.file.RocketLoadException;
import info.openrocket.core.logging.Warning;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import io.github.openrocketmcp.mcp.ToolException;

/**
 * Open OpenRocket documents, addressed by short ids ("d1", "d2", ...). Edits stay in memory until saved.
 */
public final class Designs {

	public static final String[] EXAMPLES = {
			"A simple model rocket", "Two stage high power rocket", "Dual parachute deployment",
			"Airstart timing", "Clustered motors", "Three stage low power rocket", "Parallel booster staging",
			"Chute release", "Deployable payload", "ARC payload rocket", "Tube fin rocket", "3D printable nose cone and fins",
			"Base drag hack (short-wide)", "Pods--airframes and winglets", "Pods--powered with recovery deployment" };

	public static final class Design {
		public final String id;
		public final OpenRocketDocument doc;
		public Path path;
		public final String origin;
		/** Edits made in this session, for undo / redo. */
		public final History history = new History();
		/** What OpenRocket reported while reading the file (older versions, RockSim imports); empty for most files. */
		public final List<String> loadWarnings = new ArrayList<>();
		/** The file it was imported from when that is not an .ork (a RockSim .rkt); it is saved as .ork elsewhere. */
		public Path importedFrom;

		Design(String id, OpenRocketDocument doc, Path path, String origin) {
			this.id = id;
			this.doc = doc;
			this.path = path;
			this.origin = origin;
		}

		public String name() {
			return doc.getRocket().getName();
		}
	}

	private final Map<String, Design> designs = new LinkedHashMap<>();
	private int counter;

	/**
	 * A design file read from disk. One that cannot be read says which of the usual reasons applies: missing, a
	 * folder, empty, or not a design OpenRocket understands.
	 */
	public static OpenRocketDocument read(Path p) throws Exception {
		return read(p, null);
	}

	/** As {@link #read(Path)}, adding what OpenRocket warned about while reading to {@code warnings}. */
	static OpenRocketDocument read(Path p, List<String> warnings) throws Exception {
		if (!Files.exists(p)) {
			throw new ToolException("File not found: " + p + ". It may have been moved, renamed or deleted, or be on a drive or "
					+ "cloud folder that is not connected right now.");
		}
		if (Files.isDirectory(p)) {
			throw new ToolException(p.getFileName() + " is a folder, not a design. Choose a .ork or .rkt file inside it.");
		}
		if (Files.size(p) == 0) {
			throw new ToolException(p.getFileName() + " is empty (0 bytes): the save, download or cloud sync that made it did not "
					+ "finish. Use another copy of the design.");
		}
		try {
			GeneralRocketLoader loader = new GeneralRocketLoader(p.toFile());
			OpenRocketDocument doc = loader.load();
			if (warnings != null) {
				for (Warning w : loader.getWarnings()) {
					warnings.add(w.toString());
				}
			}
			return doc;
		} catch (RocketLoadException e) {
			String why = e.getMessage() == null ? "" : e.getMessage().replaceAll("[.\\s]+$", "");
			throw new ToolException("OpenRocket could not read " + p.getFileName() + (why.isEmpty() ? "" : " (" + why + ")")
					+ ". Either it is not an OpenRocket (.ork) or RockSim (.rkt) design, or the file is damaged or incomplete, "
					+ "for example a download or cloud sync that did not finish.", e);
		}
	}

	public synchronized Design open(Path path) throws Exception {
		OrRuntime.init();
		Path p = path.toAbsolutePath().normalize();
		List<String> warnings = new ArrayList<>();
		OpenRocketDocument doc = read(p, warnings);
		boolean ork = p.toString().toLowerCase(Locale.ROOT).endsWith(".ork");
		Design d;
		if (ork) {
			doc.setFile(p.toFile());
			doc.setSaved(true);
			d = register(doc, p, "file");
			loadAeroTable(d);
		} else {
			// An import (RockSim): never written back over the original; save_design writes an .ork beside it.
			doc.setSaved(false);
			d = register(doc, null, "import:" + p.getFileName());
			d.importedFrom = p;
		}
		d.loadWarnings.addAll(warnings);
		return d;
	}

	public synchronized Design openExample(String name) throws Exception {
		OrRuntime.init();
		String match = null;
		for (String e : EXAMPLES) {
			if (e.equalsIgnoreCase(name) || e.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
				match = e;
				break;
			}
		}
		if (match == null) {
			throw new ToolException("Unknown example '" + name + "'. Available: " + String.join(", ", EXAMPLES));
		}
		Path tmp = Files.createTempFile("openrocket-example-", ".ork");
		try (InputStream in = Designs.class.getResourceAsStream("/datafiles/examples/" + match + ".ork")) {
			if (in == null) {
				throw new ToolException("Example not bundled with this OpenRocket version: " + match);
			}
			Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
		}
		OpenRocketDocument doc = new GeneralRocketLoader(tmp.toFile()).load();
		tmp.toFile().deleteOnExit();
		return register(doc, null, "example:" + match);
	}

	public synchronized Design create(String name) {
		OrRuntime.init();
		OpenRocketDocument doc = OpenRocketDocumentFactory.createNewRocket();
		if (name != null && !name.isBlank()) {
			doc.getRocket().setName(name);
		}
		return register(doc, null, "new");
	}

	private Design register(OpenRocketDocument doc, Path path, String origin) {
		boolean saved = doc.isSaved();
		repeatable(doc);
		doc.setSaved(saved);
		String id = "d" + (++counter);
		Design d = new Design(id, doc, path, origin);
		for (FlightConfiguration fc : doc.getRocket().getFlightConfigurations()) {
			Analysis.settle(fc);
		}
		designs.put(id, d);
		return d;
	}

	/**
	 * OpenRocket does not store a simulation's random seed in the file, so each time a design was opened its turbulence,
	 * and with it tilt, drift and landing points, came out a little different. Every simulation of an opened design gets
	 * the same seed as the simulations this server creates, so the same file gives the same answers in every session.
	 */
	static void repeatable(OpenRocketDocument doc) {
		for (Simulation s : doc.getSimulations()) {
			s.getOptions().setRandomSeed(Sims.NEW_SIMULATION_SEED);
		}
	}

	/** Imported RASAero / aero tables (whole vehicle and stage stacks) live next to the design (rocket.aero.json) and come back on open. */
	static void loadAeroTable(Design d) throws IOException {
		Path f = AeroTable.sidecar(d.path);
		if (Files.exists(f)) {
			AeroTable.load(d.doc.getRocket(), Files.readString(f));
		}
	}

	static void saveAeroTable(Design d) throws IOException {
		Path f = AeroTable.sidecar(d.path);
		String json = AeroTable.toJson(d.doc.getRocket());
		if (json != null) {
			Files.writeString(f, json);
		} else {
			Files.deleteIfExists(f); // the table was cleared since the design was opened
		}
	}

	/** Resolves a design id; when omitted and exactly one design is open, returns that one. */
	public synchronized Design get(String id) {
		if (id == null || id.isBlank()) {
			if (designs.size() == 1) {
				return designs.values().iterator().next();
			}
			throw new ToolException(designs.isEmpty()
					? "No design is open. Use open_design first."
					: "Several designs are open; pass designId (one of " + designs.keySet() + ").");
		}
		Design d = designs.get(id);
		if (d == null) {
			throw new ToolException("Unknown designId '" + id + "'. Open designs: " + designs.keySet());
		}
		return d;
	}

	public synchronized List<Design> all() {
		return new ArrayList<>(designs.values());
	}

	public synchronized void close(String id) {
		Design d = designs.remove(id);
		if (d == null) {
			throw new ToolException("Unknown designId '" + id + "'.");
		}
		// Its imported aero table is kept by rocket id: drop it unless another open copy of the file still uses it.
		String rid = d.doc.getRocket().getID().toString();
		if (designs.values().stream().noneMatch(o -> o.doc.getRocket().getID().toString().equals(rid))) {
			AeroTable.clear(d.doc.getRocket());
		}
		Variants.forget(d.doc.getSimulations());
	}

	public synchronized Path save(Design d, Path target) throws Exception {
		Path p = target != null ? target.toAbsolutePath().normalize() : d.path;
		if (p == null) {
			throw new ToolException(d.importedFrom != null
					? "This design was imported from " + d.importedFrom.getFileName() + "; pass a path ending in .ork, e.g. \""
							+ d.importedFrom.getFileName().toString().replaceAll("\\.[^.]+$", "") + ".ork\"."
					: "This design has no file yet; pass a path ending in .ork.");
		}
		if (!p.toString().toLowerCase(Locale.ROOT).endsWith(".ork")) {
			throw new ToolException(Files.isDirectory(p)
					? p + " is a folder: give the design a file name ending in .ork inside it."
					: "Designs are saved as OpenRocket files: the name must end in .ork (got " + p.getFileName() + ").");
		}
		try {
			if (p.getParent() != null) {
				Files.createDirectories(p.getParent());
			}
			new GeneralRocketSaver().save(p.toFile(), d.doc);
		} catch (IOException e) {
			throw ToolException.system("Could not save to " + p + " (" + reason(e) + "). The folder may be "
					+ "read-only or need administrator rights, the disk may be full, or the file may be open in another program. "
					+ "The design is still open here: save it somewhere else.", e);
		}
		d.doc.setFile(p.toFile());
		d.doc.setSaved(true);
		d.path = p;
		saveAeroTable(d);
		return p;
	}

	/** The system's reason for a failed write, without the path it repeats. */
	private static String reason(IOException e) {
		String kind = e instanceof java.nio.file.AccessDeniedException ? "access denied"
				: e instanceof java.nio.file.NoSuchFileException ? "the folder does not exist"
				: e instanceof java.nio.file.FileSystemException f && f.getReason() != null ? f.getReason() : null;
		if (kind != null) {
			return kind;
		}
		String m = e.getMessage();
		return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
	}
}
