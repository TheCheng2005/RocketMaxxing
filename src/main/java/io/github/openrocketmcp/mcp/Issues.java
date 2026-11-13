package io.github.openrocketmcp.mcp;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.openrocketmcp.mcp.ToolException.Kind;

/**
 * What went wrong in a tool call, or what to watch in its result, sorted by whose problem it is ({@link Kind}): the
 * request (input), a result that cannot be trusted as given (output), the computer or the engine (system), or the
 * rocket's flight (physics). They travel in the result's {@code _meta} under {@link #META_KEY}, next to the text a model
 * reads, so the app can show each where it belongs (the form field, the result, the problems list) and offer a report
 * for engine bugs.
 */
public final class Issues {
	private Issues() {
	}

	public static final String META_KEY = "io.github.openrocketmcp/issues";

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Pattern QUOTED = Pattern.compile("'([A-Za-z_][A-Za-z0-9_]*)'");
	private static final Pattern LEADING = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\b");
	private static final Pattern VALID = Pattern.compile("not a valid ([A-Za-z_][A-Za-z0-9_]*)");
	private static final Pattern QUOTED_VALUE = Pattern.compile("'([^']{1,120})'");
	/** A problem about how far the result can be trusted, not about the flight. */
	private static final Pattern OUTPUT_PROBLEM = Pattern.compile(
			"beyond OpenRocket's aerodynamics|extrapolat|outside (?:the |its )?(?:validated|valid) range|not validated|"
					+ "not be (?:entirely )?accurate|inaccurate",
			Pattern.CASE_INSENSITIVE);
	private static final int MAX_PATHS = 8;

	public static JsonObject issue(Kind kind, String severity, String text) {
		JsonObject o = new JsonObject();
		o.addProperty("kind", kind.id());
		o.addProperty("severity", severity);
		o.addProperty("text", text);
		return o;
	}

	/**
	 * The argument of the tool that an error message is about: a quoted argument name, "not a valid name", a value that
	 * was given for one argument only ("No component 'Wings'"), or a name the message starts with.
	 */
	public static String field(String message, JsonObject schema, JsonObject args) {
		if (message == null || schema == null || !schema.has("properties")) {
			return null;
		}
		Set<String> props = schema.getAsJsonObject("properties").keySet();
		Matcher m = QUOTED.matcher(message);
		while (m.find()) {
			if (props.contains(m.group(1))) {
				return m.group(1);
			}
		}
		Matcher valid = VALID.matcher(message);
		if (valid.find() && props.contains(valid.group(1))) {
			return valid.group(1);
		}
		if (args != null) {
			Matcher q = QUOTED_VALUE.matcher(message);
			while (q.find()) {
				String hit = null;
				for (Map.Entry<String, JsonElement> e : args.entrySet()) {
					if (props.contains(e.getKey()) && e.getValue().isJsonPrimitive() && e.getValue().getAsString().equals(q.group(1))) {
						hit = hit == null ? e.getKey() : "";
					}
				}
				if (hit != null && !hit.isEmpty()) {
					return hit;
				}
			}
		}
		Matcher lead = LEADING.matcher(message);
		if (lead.find() && props.contains(lead.group(1))) {
			return lead.group(1);
		}
		// A complaint about a file's content is about the one file (or pasted text) that was given.
		if (args != null && FILE_CONTENT.matcher(message).find()) {
			String given = null;
			for (String key : new String[] { "path", "csv", "baselinePath", "file", "url", "pageText" }) {
				if (props.contains(key) && args.has(key) && !args.get(key).isJsonNull()) {
					given = given == null ? key : "";
				}
			}
			if (given != null && !given.isEmpty()) {
				return given;
			}
		}
		return null;
	}

	private static final java.util.regex.Pattern FILE_CONTENT = java.util.regex.Pattern
			.compile("(?i)\\brows?\\b|columns?|header|could not read|could not tell|not found:|is empty|is a folder|the (flight )?log\\b|table needs");

	/** Physics, or output when the problem is that the model is used beyond what it is valid for. */
	public static Kind kindOf(String problem) {
		return OUTPUT_PROBLEM.matcher(problem).find() ? Kind.OUTPUT : Kind.PHYSICS;
	}

	/**
	 * Notes on a successful result: each problem the engine listed (PROBLEMS / problems lists of text) and values it
	 * could not compute (NaN or infinite numbers, left blank in the app).
	 */
	public static JsonArray ofResult(JsonElement result) {
		JsonArray out = new JsonArray();
		Set<String> problems = new LinkedHashSet<>();
		List<String> nonFinite = new ArrayList<>();
		walk(result, "", problems, nonFinite);
		for (String p : problems) {
			Kind k = kindOf(p);
			boolean stopped = p.contains("simulation stopped");
			out.add(issue(k, stopped ? "error" : "warning", p));
		}
		if (!nonFinite.isEmpty()) {
			int n = nonFinite.size();
			List<String> shown = nonFinite.subList(0, Math.min(n, MAX_PATHS));
			out.add(issue(Kind.OUTPUT, "warning", (n == 1 ? "1 value" : n + " values") + " could not be computed and "
					+ (n == 1 ? "is" : "are") + " left blank: " + String.join(", ", shown) + (n > MAX_PATHS ? ", ..." : "")
					+ ". Usually the flight did not reach that point (no deployment, no apogee) or an input is missing."));
		}
		return out;
	}

	private static void walk(JsonElement e, String path, Set<String> problems, List<String> nonFinite) {
		if (e == null || e.isJsonNull()) {
			return;
		}
		if (e.isJsonObject()) {
			for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
				String key = en.getKey();
				JsonElement v = en.getValue();
				if ((key.equals("PROBLEMS") || key.equals("problems")) && v.isJsonArray()) {
					for (JsonElement p : v.getAsJsonArray()) {
						if (p.isJsonPrimitive()) {
							problems.add(p.getAsString());
						}
					}
					continue;
				}
				walk(v, path.isEmpty() ? key : path + "." + key, problems, nonFinite);
			}
		} else if (e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			for (int i = 0; i < a.size(); i++) {
				walk(a.get(i), path + "[" + i + "]", problems, nonFinite);
			}
		} else if (e.getAsJsonPrimitive().isNumber()) {
			double d = e.getAsDouble();
			if (!Double.isFinite(d)) {
				nonFinite.add(path.isEmpty() ? "the result" : path);
			}
		}
	}

	/** A short ID for an engine bug, in the log next to its stack trace and in the report the user sends. */
	public static String newId() {
		byte[] b = new byte[4];
		RANDOM.nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	/** What a bug report needs: versions, the tool, and where it failed (no design data). */
	public static String report(String server, String version, String tool, Throwable e) {
		StringWriter s = new StringWriter();
		PrintWriter w = new PrintWriter(s);
		w.println(server + " " + version + ", Java " + System.getProperty("java.version") + ", "
				+ System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
		w.println("Tool: " + tool);
		w.println(e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage().lines().findFirst().orElse("")));
		StackTraceElement[] st = e.getStackTrace();
		for (int i = 0; i < Math.min(st.length, 25); i++) {
			w.println("  at " + st[i]);
		}
		Throwable c = e.getCause();
		for (int depth = 0; c != null && c != e && depth < 3; depth++, c = c.getCause()) {
			w.println("Caused by " + c.getClass().getName() + (c.getMessage() == null ? "" : ": " + c.getMessage()));
			StackTraceElement[] cs = c.getStackTrace();
			for (int i = 0; i < Math.min(cs.length, 8); i++) {
				w.println("  at " + cs[i]);
			}
		}
		w.flush();
		return s.toString().stripTrailing();
	}

	/** How an exception that is not a {@link ToolException} reads to the user. */
	static Kind kindOfUnexpected(Exception e) {
		if (e instanceof com.google.gson.JsonParseException || e instanceof NumberFormatException
				|| e instanceof java.nio.file.NoSuchFileException
				|| e instanceof IllegalArgumentException && e.getMessage() != null && !e.getMessage().isBlank()) {
			return Kind.INPUT;
		}
		return Kind.SYSTEM;
	}

	/** True when only a bug in the engine explains the exception (not the network, files or the request). */
	static boolean isBug(Exception e) {
		return kindOfUnexpected(e) == Kind.SYSTEM && !(e instanceof java.io.IOException)
				&& !(e instanceof java.io.UncheckedIOException);
	}
}
