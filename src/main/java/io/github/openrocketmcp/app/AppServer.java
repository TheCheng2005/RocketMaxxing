package io.github.openrocketmcp.app;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.openrocketmcp.mcp.Log;
import io.github.openrocketmcp.mcp.McpServer;

/**
 * The desktop app's local web server, on 127.0.0.1 only: the interface's files, and {@code POST /rpc} for tool calls
 * (JSON-RPC, the same messages the MCP server takes). Tool calls need the token that is generated for each launch and
 * handed to the page in its address, so other programs and web pages on the machine cannot drive the app.
 */
public final class AppServer {
	static final int MAX_BODY = 8 << 20;
	static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
			"css", "text/css; charset=utf-8", "svg", "image/svg+xml", "png", "image/png", "json", "application/json",
			"woff2", "font/woff2", "ico", "image/x-icon", "map", "application/json");

	private final McpServer server;
	private final Path ui;
	private final String token;
	private final List<String> extraOrigins;
	private volatile Path files;
	private HttpServer http;
	private String origin;

	/** {@code ui}: folder of the built interface (null: the copy inside the jar, if any). */
	public AppServer(McpServer server, Path ui, List<String> extraOrigins) {
		this.server = server;
		this.ui = ui;
		this.extraOrigins = extraOrigins;
		byte[] b = new byte[24];
		new SecureRandom().nextBytes(b);
		this.token = HexFormat.of().formatHex(b);
	}

	public String token() {
		return token;
	}

	/** Lets the page show what the engine writes (videos, pictures, drawings) from this folder: GET /file?path=...&token=... */
	public void files(Path workspace) {
		this.files = workspace.toAbsolutePath().normalize();
	}

	public InetSocketAddress start(int port) throws IOException {
		http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		http.createContext("/", this::handle);
		http.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
		http.start();
		InetSocketAddress a = http.getAddress();
		origin = "http://127.0.0.1:" + a.getPort();
		return a;
	}

	public String url() {
		return origin + "/#token=" + token;
	}

	public void stop() {
		if (http != null) {
			http.stop(0);
		}
	}

	private void handle(HttpExchange ex) throws IOException {
		try (ex) {
			try {
				route(ex);
			} catch (RuntimeException | LinkageError e) {
				Log.error("App request failed", e);
				try {
					send(ex, 500, "text/plain", "Internal error".getBytes(StandardCharsets.UTF_8));
				} catch (IOException | RuntimeException ignored) {
					// the response had already started
				}
			}
		}
	}

	private void route(HttpExchange ex) throws IOException {
		var h = ex.getResponseHeaders();
		h.add("X-Content-Type-Options", "nosniff");
		h.add("Referrer-Policy", "no-referrer");
		String path = ex.getRequestURI().getRawPath();
		if (path.equals("/rpc")) {
			rpc(ex);
			return;
		}
		if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) {
			send(ex, 405, "text/plain", "Method not allowed".getBytes(StandardCharsets.UTF_8));
			return;
		}
		if (path.equals("/health")) {
			send(ex, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
			return;
		}
		if (path.equals("/file")) {
			output(ex);
			return;
		}
		file(ex, path);
	}

	private boolean originOk(HttpExchange ex) {
		String o = ex.getRequestHeaders().getFirst("Origin");
		if (o == null) {
			return true; // not a browser page making a cross-site call
		}
		return o.equals(origin) || o.equals(origin.replace("127.0.0.1", "localhost")) || extraOrigins.contains(o);
	}

	private boolean tokenOk(HttpExchange ex) {
		String auth = ex.getRequestHeaders().getFirst("Authorization");
		if (auth == null || !auth.startsWith("Bearer ")) {
			return false;
		}
		return MessageDigest.isEqual(auth.substring(7).trim().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
	}

	private void rpc(HttpExchange ex) throws IOException {
		if (!ex.getRequestMethod().equals("POST")) {
			send(ex, 405, "text/plain", "Use POST".getBytes(StandardCharsets.UTF_8));
			return;
		}
		if (!originOk(ex)) {
			send(ex, 403, "text/plain", "Origin not allowed".getBytes(StandardCharsets.UTF_8));
			return;
		}
		if (!tokenOk(ex)) {
			send(ex, 401, "text/plain", "Missing or wrong token".getBytes(StandardCharsets.UTF_8));
			return;
		}
		byte[] body = ex.getRequestBody().readNBytes(MAX_BODY + 1);
		if (body.length > MAX_BODY) {
			send(ex, 413, "text/plain", "Too large".getBytes(StandardCharsets.UTF_8));
			return;
		}
		JsonObject r = server.handleLine(new String(body, StandardCharsets.UTF_8));
		send(ex, 200, "application/json", (r == null ? "{}" : r.toString()).getBytes(StandardCharsets.UTF_8));
	}

	private void file(HttpExchange ex, String path) throws IOException {
		String rel = path.equals("/") ? "index.html" : path.substring(1);
		if (rel.contains("..") || rel.contains("\\") || rel.contains("%")) {
			send(ex, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
			return;
		}
		byte[] bytes = null;
		if (ui != null) {
			Path f = ui.resolve(rel).normalize();
			if (f.startsWith(ui) && Files.isRegularFile(f)) {
				bytes = Files.readAllBytes(f);
			}
		} else {
			try (InputStream in = AppServer.class.getResourceAsStream("/rocketmaxxing/" + rel)) {
				if (in != null) {
					bytes = in.readAllBytes();
				}
			}
		}
		if (bytes == null) {
			send(ex, 404, "text/plain", ("Not found" + (ui == null && rel.equals("index.html")
					? ": the interface is not built. Run ./gradlew rocketmaxxing" : "")).getBytes(StandardCharsets.UTF_8));
			return;
		}
		String ext = rel.substring(rel.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
		// Hashed asset names never change: cache them; the page itself always comes fresh.
		ex.getResponseHeaders().add("Cache-Control", rel.startsWith("assets/") ? "max-age=31536000, immutable" : "no-store");
		if (rel.equals("index.html")) {
			ex.getResponseHeaders().add("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; "
					+ "style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'");
		}
		send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), bytes);
	}

	static final Map<String, String> OUTPUT_TYPES = Map.of("png", "image/png", "gif", "image/gif", "jpg", "image/jpeg",
			"svg", "image/svg+xml", "mp4", "video/mp4", "webm", "video/webm");

	/**
	 * A picture or video the engine wrote in the designs folder. An {@code <img>} or {@code <video>} cannot send the
	 * Authorization header, so the token comes in the query; only those media types are served, never as a page that
	 * could run script ("sandbox"), and only from inside the folder (links that lead out of it are refused).
	 */
	private void output(HttpExchange ex) throws IOException {
		Map<String, String> q = query(ex.getRequestURI().getRawQuery());
		String given = q.getOrDefault("token", "");
		if (!MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)) || files == null) {
			send(ex, 403, "text/plain", "Forbidden".getBytes(StandardCharsets.UTF_8));
			return;
		}
		Path f;
		try {
			Path p = Path.of(q.getOrDefault("path", ""));
			f = (p.isAbsolute() ? p : files.resolve(p)).toRealPath();
		} catch (IOException | RuntimeException e) {
			send(ex, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
			return;
		}
		String name = f.getFileName().toString();
		String type = OUTPUT_TYPES.get(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
		if (!f.startsWith(files.toRealPath()) || !Files.isRegularFile(f) || type == null) {
			send(ex, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
			return;
		}
		var h = ex.getResponseHeaders();
		h.add("Content-Security-Policy", "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; sandbox");
		h.add("Cache-Control", "no-store");
		h.add("Accept-Ranges", "bytes");
		long size = Files.size(f);
		// Videos are fetched in ranges (seeking): serve the one asked for.
		String range = ex.getRequestHeaders().getFirst("Range");
		java.util.regex.Matcher m = range == null ? null : java.util.regex.Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range);
		if (m != null && m.matches() && !(m.group(1).isEmpty() && m.group(2).isEmpty())) {
			long start, end;
			if (m.group(1).isEmpty()) {
				start = Math.max(0, size - Long.parseLong(m.group(2)));
				end = size - 1;
			} else {
				start = Long.parseLong(m.group(1));
				end = m.group(2).isEmpty() ? size - 1 : Math.min(size - 1, Long.parseLong(m.group(2)));
			}
			if (start > end || start >= size) {
				h.add("Content-Range", "bytes */" + size);
				send(ex, 416, "text/plain", new byte[0]);
				return;
			}
			h.add("Content-Range", "bytes " + start + "-" + end + "/" + size);
			h.add("Content-Type", type);
			ex.sendResponseHeaders(206, end - start + 1);
			try (var in = Files.newInputStream(f); var out = ex.getResponseBody()) {
				in.skipNBytes(start);
				byte[] buf = new byte[64 << 10];
				long left = end - start + 1;
				while (left > 0) {
					int n = in.read(buf, 0, (int) Math.min(buf.length, left));
					if (n < 0) {
						break;
					}
					out.write(buf, 0, n);
					left -= n;
				}
			}
			return;
		}
		h.add("Content-Type", type);
		boolean head = ex.getRequestMethod().equals("HEAD");
		ex.sendResponseHeaders(200, head ? -1 : size);
		if (!head) {
			try (var out = ex.getResponseBody()) {
				Files.copy(f, out);
			}
		}
	}

	private static Map<String, String> query(String raw) {
		Map<String, String> q = new java.util.HashMap<>();
		if (raw != null) {
			for (String part : raw.split("&")) {
				int eq = part.indexOf('=');
				if (eq > 0) {
					q.put(java.net.URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
							java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
				}
			}
		}
		return q;
	}

	private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
		ex.getResponseHeaders().add("Content-Type", type);
		boolean head = ex.getRequestMethod().equals("HEAD");
		ex.sendResponseHeaders(code, head ? -1 : body.length);
		if (!head) {
			ex.getResponseBody().write(body);
		}
	}
}
