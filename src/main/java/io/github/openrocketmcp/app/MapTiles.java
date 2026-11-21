package io.github.openrocketmcp.app;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Map tiles for the launch planner's map, fetched by the app (the page may only load from the app itself) and kept on
 * disk, so a site looked at once still shows at the field without internet. Two sources: satellite imagery (Esri World
 * Imagery) and the street map (OpenStreetMap), each with its attribution shown on the map.
 */
final class MapTiles {
	private MapTiles() {
	}

	record Source(String url, String ext, String type) {
	}

	static final Map<String, Source> SOURCES = Map.of(
			"satellite", new Source("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", "jpg", "image/jpeg"),
			"map", new Source("https://tile.openstreetmap.org/{z}/{x}/{y}.png", "png", "image/png"));

	/** Kept tiles are fetched again after this long (when the internet is there). */
	static final Duration KEEP = Duration.ofDays(30);

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL).build();

	static Path dir() {
		return Instance.dir().resolve("tiles");
	}

	/** The tile's bytes, kept or fetched; null when there is none (out of range, offline and never fetched). */
	static byte[] tile(String source, int z, int x, int y) {
		Source s = SOURCES.get(source);
		if (s == null || z < 0 || z > 19 || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z)) {
			return null;
		}
		Path f = dir().resolve(source).resolve(Integer.toString(z)).resolve(Integer.toString(x)).resolve(y + "." + s.ext());
		byte[] kept = null;
		try {
			if (Files.isRegularFile(f)) {
				kept = Files.readAllBytes(f);
				if (Files.getLastModifiedTime(f).toInstant().isAfter(Instant.now().minus(KEEP))) {
					return kept;
				}
			}
		} catch (IOException e) {
			// fetch it again
		}
		String url = s.url().replace("{z}", Integer.toString(z)).replace("{x}", Integer.toString(x)).replace("{y}", Integer.toString(y));
		try {
			HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
					.header("User-Agent", "RocketMaxxing/" + App.VERSION + " (rocket launch planner; +https://github.com/TheCheng2005/RocketMaxxing)")
					.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
			if (r.statusCode() != 200 || r.body().length == 0) {
				return kept;
			}
			Files.createDirectories(f.getParent());
			Path tmp = Files.createTempFile(f.getParent(), "tile", ".part");
			Files.write(tmp, r.body());
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			return r.body();
		} catch (IOException e) {
			return kept; // offline: the kept one, however old
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return kept;
		}
	}
}
