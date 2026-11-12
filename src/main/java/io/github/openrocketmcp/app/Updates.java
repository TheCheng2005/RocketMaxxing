package io.github.openrocketmcp.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Is there a newer RocketMaxxing? Asks GitHub for the project's releases (tags vX.Y or rocketmaxxing-vX.Y.Z), at most once a day
 * unless asked to (the answer is kept in a file), and finds the installer for this computer. It sends nothing but the
 * request itself, and offline it says so instead of failing. Pre-releases are offered only to someone already on one
 * (0.x versions included), so a stable version is never pushed onto a beta.
 */
final class Updates {
	static final String REPO = "TheCheng2005/RocketMaxxing";
	/** A release tag is the version after one of these: v1.5, rocketmaxxing-v1.5.0. */
	static final String[] TAGS = { "rocketmaxxing-v", "v" };
	static final long DAY = 24 * 3600 * 1000L;

	private Updates() {
	}

	static String url() {
		return System.getProperty("rocketmaxxing.updates.url", "https://api.github.com/repos/" + REPO + "/releases?per_page=30");
	}

	/** The check: from the cache when it is less than a day old (unless {@code force}), else from GitHub. */
	static Map<String, Object> check(String current, Path cache, boolean force) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("current", current);
		String body = null;
		long checkedAt = 0;
		if (!force && Files.isRegularFile(cache)) {
			try {
				JsonObject c = JsonParser.parseString(Files.readString(cache, StandardCharsets.UTF_8)).getAsJsonObject();
				if (System.currentTimeMillis() - c.get("checkedAt").getAsLong() < DAY && url().equals(c.get("url").getAsString())) {
					body = c.get("body").getAsString();
					checkedAt = c.get("checkedAt").getAsLong();
					out.put("fromCache", true);
				}
			} catch (Exception e) {
				// unreadable cache: ask again
			}
		}
		if (body == null) {
			try {
				HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
						.followRedirects(HttpClient.Redirect.NORMAL).build();
				HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(url())).timeout(Duration.ofSeconds(8))
						.header("Accept", "application/vnd.github+json").header("User-Agent", "RocketMaxxing/" + current).GET().build(),
						HttpResponse.BodyHandlers.ofString());
				if (r.statusCode() != 200) {
					out.put("error", "GitHub answered " + r.statusCode() + "; try again later.");
					return out;
				}
				body = r.body();
				checkedAt = System.currentTimeMillis();
				JsonObject c = new JsonObject();
				c.addProperty("checkedAt", checkedAt);
				c.addProperty("url", url());
				c.addProperty("body", body);
				try {
					Files.createDirectories(cache.getParent());
					Files.writeString(cache, c.toString(), StandardCharsets.UTF_8);
				} catch (Exception e) {
					// no cache: the next start asks again
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				out.put("error", "Interrupted.");
				return out;
			} catch (Exception e) {
				out.put("error", "Could not reach GitHub to check for updates (offline?).");
				return out;
			}
		}
		out.put("checkedAt", checkedAt);
		try {
			out.putAll(newest(JsonParser.parseString(body).getAsJsonArray(), current, platform()));
		} catch (RuntimeException e) {
			out.put("error", "GitHub's answer could not be read.");
		}
		return out;
	}

	/** The newest release this version should be offered, and whether it is newer: latest, newer, url, download. */
	static Map<String, Object> newest(JsonArray releases, String current, String platform) {
		boolean betas = isPre(current);
		JsonObject best = null;
		String bestVersion = null;
		for (JsonElement e : releases) {
			JsonObject r = e.getAsJsonObject();
			String tag = str(r, "tag_name");
			String v = version(tag);
			if (v == null || bool(r, "draft") || (bool(r, "prerelease") && !betas)) {
				continue;
			}
			if (bestVersion == null || compare(v, bestVersion) > 0) {
				best = r;
				bestVersion = v;
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		if (best == null) {
			out.put("latest", null);
			out.put("newer", false);
			return out;
		}
		out.put("latest", bestVersion);
		out.put("newer", compare(bestVersion, current) > 0);
		out.put("url", str(best, "html_url"));
		out.put("published", str(best, "published_at"));
		out.put("prerelease", bool(best, "prerelease"));
		if (best.has("assets") && best.get("assets").isJsonArray()) {
			for (JsonElement a : best.getAsJsonArray("assets")) {
				String name = str(a.getAsJsonObject(), "name");
				if (name != null && name.toLowerCase(Locale.ROOT).endsWith(platform)) {
					out.put("download", str(a.getAsJsonObject(), "browser_download_url"));
					out.put("file", name);
				}
			}
		}
		return out;
	}

	/** The end of this computer's installer name on the Releases page (release.yml names them so). */
	static String platform() {
		return platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
	}

	static String platform(String os, String arch) {
		String o = os.toLowerCase(Locale.ROOT), a = arch.toLowerCase(Locale.ROOT);
		if (o.contains("win")) {
			return "-windows-x64.msi";
		}
		if (o.contains("mac")) {
			return a.contains("aarch64") || a.contains("arm") ? "-macos-apple-silicon.dmg" : "-macos-intel.dmg";
		}
		return "-linux-x64.deb";
	}

	/** The version a release tag names (v1.5 gives 1.5), or null when the tag is not a version. */
	static String version(String tag) {
		if (tag != null) {
			for (String prefix : TAGS) {
				if (tag.startsWith(prefix) && tag.length() > prefix.length() && Character.isDigit(tag.charAt(prefix.length()))) {
					return tag.substring(prefix.length());
				}
			}
		}
		return null;
	}

	/** Version order: numbers part by part; a release ranks above its own pre-releases (0.2.0 > 0.2.0-beta.1). */
	static int compare(String a, String b) {
		String[] pa = a.split("-", 2), pb = b.split("-", 2);
		String[] na = pa[0].split("\\."), nb = pb[0].split("\\.");
		for (int i = 0; i < Math.max(na.length, nb.length); i++) {
			int x = i < na.length ? num(na[i]) : 0, y = i < nb.length ? num(nb[i]) : 0;
			if (x != y) {
				return Integer.compare(x, y);
			}
		}
		if (pa.length != pb.length) {
			return pa.length == 1 ? 1 : -1;
		}
		return pa.length == 1 ? 0 : comparePre(pa[1], pb[1]);
	}

	/** Pre-release names part by part, numbers as numbers: beta.10 is after beta.2, and rc after beta. */
	private static int comparePre(String a, String b) {
		String[] xa = a.split("\\."), xb = b.split("\\.");
		for (int i = 0; i < Math.min(xa.length, xb.length); i++) {
			boolean na = xa[i].matches("\\d{1,9}"), nb = xb[i].matches("\\d{1,9}");
			int c = na && nb ? Integer.compare(Integer.parseInt(xa[i]), Integer.parseInt(xb[i]))
					: na != nb ? (na ? -1 : 1) : xa[i].compareTo(xb[i]);
			if (c != 0) {
				return c;
			}
		}
		return Integer.compare(xa.length, xb.length);
	}

	static boolean isPre(String v) {
		return v.startsWith("0.") || v.contains("-");
	}

	private static int num(String s) {
		try {
			return Integer.parseInt(s.replaceAll("\\D.*$", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
	}

	private static boolean bool(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
	}
}
