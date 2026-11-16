package io.github.openrocketmcp.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.file.GeneralRocketSaver;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.Versions;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.AeroTable;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.ImportCheck;
import io.github.openrocketmcp.or.Motors;
import io.github.openrocketmcp.standards.Standards;

/**
 * A RocketMaxxing project (.rmx): one file with the design and what the app knows about it, for the team to keep or
 * pass on. It is a zip with the OpenRocket design inside it whole ({@code design.ork}, which opens in OpenRocket once
 * taken out), and beside it the RASAero aero table, the team standards and targets, the motor files the design uses
 * that are not in OpenRocket's database, the progress log, flight logs, saved plots and the units it was made with.
 * Opening one brings the design, its motors and aero table in at once; standards, units and plots are offered, never
 * put over the team's own.
 */
public final class Projects {
	private Projects() {
	}

	public static final String EXT = ".rmx";
	public static final String FORMAT = "rocketmaxxing-project";
	/** The format version written. A newer file still opens: what this version does not know is left out, and said. */
	public static final int VERSION = 1;
	/** A project is a design and its companions: anything beyond this is refused (and a zip that expands past it). */
	static final long MAX_BYTES = 200L * 1024 * 1024;
	static final long MAX_LOG = 25L * 1024 * 1024;

	public static boolean is(Path p) {
		return p != null && p.getFileName() != null && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXT);
	}

	static Path motorsDir(Context ctx) {
		return ctx.workspace().resolve("motors");
	}

	/** The motors a design's flight configurations use, by digest. */
	static Set<String> usedMotors(OpenRocketDocument doc) {
		Set<String> out = new HashSet<>();
		for (RocketComponent c : doc.getRocket()) {
			if (c instanceof MotorMount mm && mm.isMotorMount()) {
				for (FlightConfigurationId id : doc.getRocket().getIds()) {
					Motor m = mm.getMotorConfig(id).getMotor();
					if (m instanceof ThrustCurveMotor t) {
						out.add(t.getDigest());
					}
				}
			}
		}
		return out;
	}

	/** The team's motor files (motors/) holding a motor the design uses. */
	static List<Path> motorFiles(Context ctx, OpenRocketDocument doc) throws IOException {
		Set<String> used = usedMotors(doc);
		List<Path> out = new ArrayList<>();
		Path dir = motorsDir(ctx);
		if (used.isEmpty() || !Files.isDirectory(dir)) {
			return out;
		}
		try (var files = Files.list(dir)) {
			for (Path f : files.sorted().toList()) {
				String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
				if (!(n.endsWith(".eng") || n.endsWith(".rse") || n.endsWith(".zip"))) {
					continue;
				}
				try {
					if (Motors.peek(f).stream().anyMatch(m -> used.contains(m.getDigest()))) {
						out.add(f);
					}
				} catch (IOException | RuntimeException e) {
					// a file that does not read is not this design's motor
				}
			}
		}
		return out;
	}

	/**
	 * Writes the project. {@code extras} is what only the app knows: {@code plots} and {@code settings} (JSON text) and
	 * {@code flightLogs} (files). The file is written beside the target and moved into place, so a failed save never
	 * leaves half a project; the design is then tied to it (Save writes it again).
	 */
	public static Map<String, Object> save(Context ctx, Designs.Design d, Path target, JsonObject extras) throws IOException {
		Path p = target.toAbsolutePath().normalize();
		if (p.getParent() != null) {
			Files.createDirectories(p.getParent());
		}
		JsonObject manifest = new JsonObject();
		manifest.addProperty("format", FORMAT);
		manifest.addProperty("version", VERSION);
		manifest.addProperty("name", d.name());
		manifest.addProperty("savedAt", Instant.now().toString());
		manifest.addProperty("app", Versions.APP);
		manifest.addProperty("engine", Versions.ENGINE);
		manifest.addProperty("openRocket", Versions.openRocket());
		JsonArray contents = new JsonArray();
		Path progress = ProjectTools.logFile(ctx, d); // before the design moves to the new file
		Path tmp = Files.createTempFile(p.getParent() != null ? p.getParent() : Path.of("."), ".rmx-", ".tmp");
		Path ork = Files.createTempFile("rmx-design-", ".ork");
		try {
			try {
				new GeneralRocketSaver().save(ork.toFile(), d.doc);
			} catch (Exception e) {
				throw new IOException("OpenRocket could not write the design (" + e.getMessage() + ")", e);
			}
			try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(tmp))) {
				put(z, "design.ork", Files.readAllBytes(ork), contents, "the OpenRocket design, with its simulations");
				String aero = AeroTable.toJson(d.doc.getRocket());
				if (aero != null) {
					put(z, "aero.json", aero.getBytes(StandardCharsets.UTF_8), contents, "the RASAero aero table");
				}
				JsonObject std = TeamFile.read(TeamFile.path(ctx));
				if (std.size() > 0) {
					put(z, "standards.json", Standards.pretty(std).getBytes(StandardCharsets.UTF_8), contents,
							"the team standards and targets");
				}
				Set<String> names = new HashSet<>();
				for (Path m : motorFiles(ctx, d.doc)) {
					if (names.add(m.getFileName().toString())) {
						put(z, "motors/" + m.getFileName(), Files.readAllBytes(m), contents, "a motor the design uses");
					}
				}
				if (Files.isRegularFile(progress)) {
					put(z, "progress.jsonl", Files.readAllBytes(progress), contents, "the progress log");
				}
				if (extras != null && extras.has("flightLogs") && extras.get("flightLogs").isJsonArray()) {
					Set<String> logNames = new HashSet<>();
					for (JsonElement e : extras.getAsJsonArray("flightLogs")) {
						Path log = ctx.path(e.getAsString()).toAbsolutePath().normalize();
						if (Files.isRegularFile(log) && Files.size(log) <= MAX_LOG && logNames.add(log.getFileName().toString())) {
							put(z, "logs/" + log.getFileName(), Files.readAllBytes(log), contents, "a flight log");
						}
					}
				}
				for (String k : List.of("plots", "settings")) {
					if (extras != null && extras.has(k) && !extras.get(k).isJsonNull()) {
						JsonElement v = extras.get(k).isJsonPrimitive() ? JsonParser.parseString(extras.get(k).getAsString()) : extras.get(k);
						put(z, k + ".json", v.toString().getBytes(StandardCharsets.UTF_8), contents,
								k.equals("plots") ? "saved plots" : "the units and number format it was made with");
					}
				}
				manifest.add("contents", contents);
				put(z, "manifest.json", Standards.pretty(manifest).getBytes(StandardCharsets.UTF_8), null, null);
			}
			Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.deleteIfExists(tmp);
			throw ToolException.system("Could not save the project to " + p + " (" + e.getMessage() + "). The folder may be read-only, "
					+ "the disk full, or the file open in another program. The design is still open here: save it somewhere else.", e);
		} finally {
			Files.deleteIfExists(ork);
		}
		d.path = p;
		d.importedFrom = null;
		d.doc.setSaved(true);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("saved", p.toString());
		out.put("format", "RocketMaxxing project");
		List<String> what = new ArrayList<>();
		contents.forEach(c -> what.add(c.getAsJsonObject().get("about").getAsString()));
		out.put("contains", what);
		return out;
	}

	private static void put(ZipOutputStream z, String name, byte[] data, JsonArray contents, String about) throws IOException {
		z.putNextEntry(new ZipEntry(name));
		z.write(data);
		z.closeEntry();
		if (contents != null) {
			JsonObject c = new JsonObject();
			c.addProperty("file", name);
			c.addProperty("about", about);
			contents.add(c);
		}
	}

	/** An entry's bytes, refusing a zip that claims to be (or turns out to be) larger than a project can be. */
	private static byte[] read(ZipFile zip, ZipEntry e, long[] total) throws IOException {
		try (InputStream in = zip.getInputStream(e)) {
			byte[] b = in.readNBytes((int) Math.min(MAX_BYTES + 1, Integer.MAX_VALUE - 8));
			total[0] += b.length;
			if (b.length > MAX_BYTES || total[0] > MAX_BYTES) {
				throw new ToolException("This project is larger than " + (MAX_BYTES >> 20) + " MB once opened: it is damaged, or not a "
						+ "RocketMaxxing project.");
			}
			return b;
		}
	}

	/** Only the file name of an entry, never a path out of the folder it is put in. */
	private static String leaf(String entry) {
		String n = entry.substring(entry.lastIndexOf('/') + 1).replaceAll("[\\\\:*?\"<>|\\p{Cntrl}]+", "_").strip();
		return n.isEmpty() || n.startsWith(".") ? "file" + n : n;
	}

	/** A free name in {@code dir} for {@code name}, or the file already there with the same bytes. */
	private static Path place(Path dir, String name, byte[] data) throws IOException {
		Files.createDirectories(dir);
		Path p = dir.resolve(name);
		int dot = name.lastIndexOf('.');
		for (int i = 2; Files.exists(p); i++) {
			if (Files.size(p) == data.length && java.util.Arrays.equals(Files.readAllBytes(p), data)) {
				return p; // the same file is there already
			}
			p = dir.resolve(dot > 0 ? name.substring(0, dot) + " (" + i + ")" + name.substring(dot) : name + " (" + i + ")");
		}
		Files.write(p, data);
		return p;
	}

	/**
	 * Opens a project: its motors join the team's (before the design, so the design finds them), then the design, its
	 * aero table and progress log. Flight logs go to the designs folder's "flight logs". What the app may offer (the
	 * project's standards, plots and units) is kept on the design for the page to ask about.
	 */
	public static Designs.Design open(Context ctx, Path file) throws Exception {
		Path p = file.toAbsolutePath().normalize();
		if (!Files.isRegularFile(p)) {
			throw new ToolException("File not found: " + p + ". It may have been moved, renamed or deleted.");
		}
		List<String> warnings = new ArrayList<>();
		JsonObject project = new JsonObject();
		JsonArray motors = new JsonArray(), logs = new JsonArray();
		Designs.Design d;
		try (ZipFile zip = new ZipFile(p.toFile())) {
			long[] total = { 0 };
			ZipEntry me = zip.getEntry("manifest.json");
			ZipEntry de = zip.getEntry("design.ork");
			if (me == null || de == null) {
				throw new ToolException(p.getFileName() + " is not a RocketMaxxing project (it has no " + (me == null ? "manifest" : "design")
						+ " inside). It may be another kind of zip file, or a project that did not finish saving.");
			}
			JsonObject manifest = JsonParser.parseString(new String(read(zip, me, total), StandardCharsets.UTF_8)).getAsJsonObject();
			if (!manifest.has("format") || !FORMAT.equals(manifest.get("format").getAsString())) {
				throw new ToolException(p.getFileName() + " is not a RocketMaxxing project.");
			}
			int version = manifest.has("version") ? manifest.get("version").getAsInt() : 1;
			if (version > VERSION) {
				warnings.add("Made by a newer RocketMaxxing (" + (manifest.has("app") ? manifest.get("app").getAsString() : "project version " + version)
						+ "): anything this version does not know about was left out. Update RocketMaxxing to open all of it.");
			}
			project.add("manifest", manifest);
			// Motors first: the design names them, and OpenRocket looks them up as it reads it.
			for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements();) {
				ZipEntry e = en.nextElement();
				if (e.isDirectory() || !e.getName().startsWith("motors/")) {
					continue;
				}
				byte[] b = read(zip, e, total);
				Path kept = place(motorsDir(ctx), leaf(e.getName()), b);
				try {
					Motors.importFile(kept);
					motors.add(kept.getFileName().toString());
				} catch (IOException | RuntimeException ex) {
					warnings.add("The motor file " + leaf(e.getName()) + " could not be read (" + ex.getMessage() + ").");
				}
			}
			// OpenRocket looks at the file a design was read from again when it saves it (for its decals), so the
			// design's copy stays until the app closes, as the bundled examples' copies do.
			Path ork = Files.createTempFile("rmx-open-", ".ork");
			ork.toFile().deleteOnExit();
			Files.write(ork, read(zip, de, total));
			OpenRocketDocument doc = Designs.read(ork, warnings);
			doc.setSaved(true);
			d = ctx.designs.adopt(doc, p, "project");
			ZipEntry ae = zip.getEntry("aero.json");
			if (ae != null) {
				warnings.addAll(AeroTable.load(doc.getRocket(), new String(read(zip, ae, total), StandardCharsets.UTF_8)));
			}
			ZipEntry pe = zip.getEntry("progress.jsonl");
			if (pe != null) {
				Path log = ProjectTools.logFile(ctx, d);
				if (!Files.exists(log)) {
					try {
						Files.write(log, read(zip, pe, total));
					} catch (IOException e) {
						warnings.add("The progress log could not be put beside the project (" + e.getMessage() + ").");
					}
				}
			}
			Path logDir = ctx.workspace().resolve("flight logs").resolve(leaf(d.name()));
			for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements();) {
				ZipEntry e = en.nextElement();
				if (!e.isDirectory() && e.getName().startsWith("logs/")) {
					logs.add(place(logDir, leaf(e.getName()), read(zip, e, total)).toString());
				}
			}
			ZipEntry se = zip.getEntry("standards.json");
			if (se != null) {
				JsonObject theirs = JsonParser.parseString(new String(read(zip, se, total), StandardCharsets.UTF_8)).getAsJsonObject();
				JsonObject ours = TeamFile.read(TeamFile.path(ctx));
				project.add("standards", theirs);
				project.addProperty("standardsDiffer", !theirs.equals(ours));
				project.addProperty("standardsSummary", summary(theirs));
			}
			for (String k : List.of("plots", "settings")) {
				ZipEntry x = zip.getEntry(k + ".json");
				if (x != null) {
					project.add(k, JsonParser.parseString(new String(read(zip, x, total), StandardCharsets.UTF_8)));
				}
			}
		} catch (java.util.zip.ZipException e) {
			throw new ToolException(p.getFileName() + " could not be read as a project (" + e.getMessage() + "): the file is damaged or "
					+ "incomplete, for example a download that did not finish.", e);
		}
		project.add("motors", motors);
		project.add("flightLogs", logs);
		d.project = project;
		d.loadWarnings.addAll(warnings);
		ImportCheck.Report report = ImportCheck.check(p, d.doc, warnings);
		List<ImportCheck.Finding> findings = new ArrayList<>(report.findings());
		if (!motors.isEmpty()) {
			findings.add(new ImportCheck.Finding("note", "Motors that came with the project, now in your motors: " + join(motors) + "."));
		}
		if (!logs.isEmpty()) {
			findings.add(new ImportCheck.Finding("note", "Flight logs that came with it, in the designs folder's \"flight logs\": "
					+ join(logs.asList().stream().map(x -> Path.of(x.getAsString()).getFileName().toString()).toList()) + "."));
		}
		if (!findings.isEmpty()) {
			d.importReport = new ImportCheck.Report(report.format(), report.file(), report.cameIn(), findings);
		}
		return d;
	}

	private static String join(JsonArray a) {
		List<String> s = new ArrayList<>();
		a.forEach(x -> s.add(x.getAsString()));
		return join(s);
	}

	private static String join(List<String> s) {
		return String.join(", ", s);
	}

	/** The project's standards in a line: the rule set and how many targets. */
	static String summary(JsonObject std) {
		List<String> parts = new ArrayList<>();
		if (std.has("ruleset")) {
			parts.add("rule set " + Standards.ruleSetName(std.get("ruleset").getAsString()));
		}
		if (std.has("targets") && std.get("targets").isJsonObject()) {
			int n = std.getAsJsonObject("targets").size();
			parts.add(n + (n == 1 ? " target" : " targets"));
		}
		if (std.has("launchSite")) {
			parts.add("a launch site");
		}
		int others = (int) std.keySet().stream().filter(k -> !List.of("ruleset", "targets", "launchSite").contains(k)).count();
		if (others > 0) {
			parts.add(others + (others == 1 ? " other section" : " other sections"));
		}
		return parts.isEmpty() ? "no changes from the defaults" : String.join(", ", parts);
	}

	/** Puts the project's standards in place of the team's, keeping the team's old file beside it (.bak). */
	public static Map<String, Object> useStandards(Context ctx, Designs.Design d) throws IOException {
		if (d.project == null || !d.project.has("standards")) {
			throw new ToolException("This design did not come from a project with standards.");
		}
		Path file = TeamFile.path(ctx);
		Path backup = null;
		if (Files.exists(file)) {
			backup = file.resolveSibling(file.getFileName() + ".bak");
			Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
		}
		JsonObject theirs = d.project.getAsJsonObject("standards");
		Standards next = Standards.fromTeam(theirs, file); // checked before anything is written
		JsonObject before = TeamFile.read(file);
		Files.createDirectories(file.toAbsolutePath().getParent());
		Files.writeString(file, Standards.pretty(theirs));
		ctx.setStandards(next);
		TeamFile.journal(ctx, file, before, theirs, "Used the standards of the project " + (d.path == null ? d.name() : d.path.getFileName()));
		d.project.addProperty("standardsDiffer", false);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("saved", file.toString());
		if (backup != null) {
			out.put("previous", backup.toString());
		}
		return out;
	}
}
