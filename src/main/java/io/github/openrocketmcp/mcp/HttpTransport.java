package io.github.openrocketmcp.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.openrocketmcp.report.Xml;

/**
 * MCP over HTTP ("Streamable HTTP" transport, JSON responses) so a whole team can share one server from claude.ai,
 * Claude Desktop or Claude Code, plus a small page to upload designs to and download reports from the shared
 * workspace.
 *
 * <pre>
 *   POST /mcp                    JSON-RPC message or batch, "Authorization: Bearer TOKEN"
 *   POST /mcp/TOKEN              the same with the token in the URL (for clients that take only a URL, e.g. claude.ai)
 *   GET  /files/TOKEN/           workspace listing + upload form;  GET /files/TOKEN/path downloads, PUT uploads
 *   GET  /health                 liveness (no token)
 * </pre>
 * Without a token (allowed only on a loopback address) the TOKEN segments are left out.
 */
public final class HttpTransport {
	public static final long MAX_MESSAGE = 16L << 20, MAX_UPLOAD = 200L << 20;

	private final McpServer server;
	private final Path workspace;
	private final String token;
	private final List<String> allowedOrigins;
	private final Gson gson = new GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();
	private HttpServer http;
	private ExecutorService pool;

	/**
	 * @param token          shared secret, or null for none (loopback only)
	 * @param allowedOrigins browser origins allowed besides the server's own host (DNS-rebinding protection)
	 */
	public HttpTransport(McpServer server, Path workspace, String token, List<String> allowedOrigins) {
		this.server = server;
		this.workspace = workspace.toAbsolutePath().normalize();
		this.token = token == null || token.isBlank() ? null : token;
		this.allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
	}

	public InetSocketAddress start(String host, int port, int threads) throws IOException {
		InetSocketAddress addr = new InetSocketAddress(host, port);
		if (token == null && !addr.getAddress().isLoopbackAddress()) {
			throw new IllegalArgumentException("Refusing to serve " + host + " without a token: set OPENROCKET_MCP_TOKEN or "
					+ "--token, or bind to 127.0.0.1.");
		}
		http = HttpServer.create(addr, 64);
		pool = Executors.newFixedThreadPool(Math.max(2, threads), r -> {
			Thread t = new Thread(r, "mcp-http");
			t.setDaemon(true);
			return t;
		});
		http.setExecutor(pool);
		http.createContext("/", this::handle);
		http.start();
		return http.getAddress();
	}

	public void stop() {
		if (http != null) {
			http.stop(0);
		}
		if (pool != null) {
			pool.shutdownNow();
		}
	}

	/** Path prefix that carries the token ("" without a token). */
	String prefix() {
		return token == null ? "" : "/" + token;
	}

	private void handle(HttpExchange ex) throws IOException {
		try (ex) {
			ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
			ex.getResponseHeaders().add("Cache-Control", "no-store");
			String path = ex.getRequestURI().getRawPath();
			if (path.equals("/health")) {
				send(ex, 200, "text/plain", "ok");
				return;
			}
			if (!originOk(ex)) {
				send(ex, 403, "text/plain", "Origin not allowed");
				return;
			}
			boolean cors = cors(ex);
			if (ex.getRequestMethod().equals("OPTIONS")) {
				send(ex, cors ? 204 : 403, "text/plain", cors ? null : "Origin not allowed");
				return;
			}
			if (path.equals("/mcp") || path.equals("/mcp/")) {
				if (!bearerOk(ex)) {
					ex.getResponseHeaders().add("WWW-Authenticate", "Bearer");
					send(ex, 401, "text/plain", "Missing or wrong token");
					return;
				}
				mcp(ex);
				return;
			}
			if (token != null && (path.equals("/mcp" + prefix()) || path.equals("/mcp" + prefix() + "/"))) {
				mcp(ex);
				return;
			}
			String files = "/files" + prefix();
			if (path.equals(files) || path.startsWith(files + "/")) {
				files(ex, path.substring(files.length()));
				return;
			}
			send(ex, 404, "text/plain", "Not found");
		} catch (RuntimeException e) {
			Log.error("HTTP request failed", e);
			try {
				send(ex, 500, "text/plain", "Internal error");
			} catch (IOException | RuntimeException ignored) {
				// response already started
			}
		}
	}

	// ------------------------------------------------------------------------------------------- MCP

	private void mcp(HttpExchange ex) throws IOException {
		String method = ex.getRequestMethod();
		if (method.equals("GET") || method.equals("DELETE")) {
			// No server-initiated stream and no sessions: every response comes back on its POST.
			ex.getResponseHeaders().add("Allow", "POST");
			send(ex, 405, "text/plain", "Use POST");
			return;
		}
		if (!method.equals("POST")) {
			send(ex, 405, "text/plain", "Use POST");
			return;
		}
		byte[] body = read(ex.getRequestBody(), MAX_MESSAGE);
		if (body == null) {
			send(ex, 413, "text/plain", "Message too large");
			return;
		}
		JsonElement msg;
		try {
			msg = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
		} catch (RuntimeException e) {
			sendJson(ex, 400, rpcError("Parse error: " + e.getMessage()));
			return;
		}
		if (msg.isJsonArray()) {
			JsonArray out = new JsonArray();
			for (JsonElement m : msg.getAsJsonArray()) {
				JsonObject r = server.handleLine(m.toString());
				if (r != null) {
					out.add(r);
				}
			}
			if (out.isEmpty()) {
				send(ex, 202, null, null);
			} else {
				sendJson(ex, 200, out);
			}
			return;
		}
		if (wantsStream(ex, msg)) {
			stream(ex, msg.getAsJsonObject());
			return;
		}
		JsonObject r = server.handleLine(msg.toString());
		if (r == null) {
			send(ex, 202, null, null); // notification or client response
		} else {
			sendJson(ex, 200, r);
		}
	}

	/** A tool call whose client asked for progress and accepts an event stream (Streamable HTTP). */
	private static boolean wantsStream(HttpExchange ex, JsonElement msg) {
		String accept = ex.getRequestHeaders().getFirst("Accept");
		if (accept == null || !accept.contains("text/event-stream") || !msg.isJsonObject()) {
			return false;
		}
		JsonObject o = msg.getAsJsonObject();
		if (!o.has("method") || !"tools/call".equals(o.get("method").getAsString()) || !o.has("params")
				|| !o.get("params").isJsonObject()) {
			return false;
		}
		JsonObject p = o.getAsJsonObject("params");
		return p.has("_meta") && p.get("_meta").isJsonObject() && p.getAsJsonObject("_meta").has("progressToken");
	}

	/** Answers one tool call as server-sent events: progress notifications while it runs, then the result. */
	private void stream(HttpExchange ex, JsonObject msg) throws IOException {
		ex.getResponseHeaders().add("Content-Type", "text/event-stream");
		ex.sendResponseHeaders(200, 0);
		try (OutputStream o = ex.getResponseBody()) {
			Consumer<JsonObject> event = m -> {
				byte[] b = ("event: message\ndata: " + compactGson.toJson(m) + "\n\n").getBytes(StandardCharsets.UTF_8);
				synchronized (o) {
					try {
						o.write(b);
						o.flush();
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}
			};
			JsonObject r = server.handle(msg.toString(), event);
			if (r != null) {
				event.accept(r);
			}
		} catch (UncheckedIOException e) {
			// the client went away
		}
	}

	private static final Gson compactGson = new GsonBuilder().disableHtmlEscaping()
			.serializeSpecialFloatingPointValues().create();

	private static JsonObject rpcError(String message) {
		JsonObject err = new JsonObject();
		err.addProperty("code", -32700);
		err.addProperty("message", message);
		JsonObject o = new JsonObject();
		o.addProperty("jsonrpc", "2.0");
		o.add("id", null);
		o.add("error", err);
		return o;
	}

	// ------------------------------------------------------------------------------------------- security

	boolean bearerOk(HttpExchange ex) {
		if (token == null) {
			return true;
		}
		String h = ex.getRequestHeaders().getFirst("Authorization");
		return h != null && h.regionMatches(true, 0, "Bearer ", 0, 7) && same(h.substring(7).trim(), token);
	}

	static boolean same(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Browser MCP clients (the MCP Inspector, an MCP Apps host) on an origin given with --allow-origin get CORS headers,
	 * so their preflight succeeds. Other origins get none: a page elsewhere, loopback included, cannot call the server
	 * from a browser.
	 */
	boolean cors(HttpExchange ex) {
		String origin = ex.getRequestHeaders().getFirst("Origin");
		if (origin == null || !(allowedOrigins.contains("*") || allowedOrigins.contains(origin))) {
			return false;
		}
		var h = ex.getResponseHeaders();
		h.add("Access-Control-Allow-Origin", origin);
		h.add("Vary", "Origin");
		h.add("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS");
		h.add("Access-Control-Allow-Headers", "content-type, authorization, mcp-protocol-version, mcp-session-id, last-event-id");
		h.add("Access-Control-Expose-Headers", "mcp-session-id");
		h.add("Access-Control-Max-Age", "600");
		return true;
	}

	/** Browsers send Origin; accept our own host, loopback and the configured origins (DNS-rebinding protection). */
	boolean originOk(HttpExchange ex) {
		String origin = ex.getRequestHeaders().getFirst("Origin");
		if (origin == null || origin.isBlank()) {
			return true;
		}
		if (allowedOrigins.contains("*") || allowedOrigins.contains(origin)) {
			return true;
		}
		try {
			URI o = URI.create(origin);
			String host = o.getHost();
			if (host == null) {
				return false;
			}
			if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1")) {
				return true;
			}
			String own = ex.getRequestHeaders().getFirst("Host");
			String ownHost = own == null ? null : own.replaceFirst(":\\d+$", "");
			return host.equalsIgnoreCase(ownHost);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** Workspace file for a URL path, or null when it leaves the workspace or touches hidden files. */
	Path resolve(String rawRel) {
		String rel = URLDecoder.decode(rawRel, StandardCharsets.UTF_8);
		while (rel.startsWith("/")) {
			rel = rel.substring(1);
		}
		if (rel.isEmpty()) {
			return workspace;
		}
		for (String seg : rel.split("/")) {
			if (seg.isEmpty() || seg.startsWith(".") || seg.contains("\\") || seg.contains(":")) {
				return null;
			}
		}
		Path p = workspace.resolve(rel).normalize();
		if (!p.startsWith(workspace)) {
			return null;
		}
		try {
			Path root = workspace.toRealPath();
			for (Path q = p; q != null; q = q.getParent()) {
				if (Files.exists(q)) {
					return q.toRealPath().startsWith(root) ? p : null;
				}
			}
		} catch (IOException e) {
			return null;
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------- files

	private void files(HttpExchange ex, String rel) throws IOException {
		Path p = resolve(rel);
		if (p == null) {
			send(ex, 403, "text/plain", "Path not allowed");
			return;
		}
		switch (ex.getRequestMethod()) {
			case "GET" -> {
				if (Files.isDirectory(p)) {
					send(ex, 200, "text/html; charset=utf-8", page(p));
				} else if (Files.isRegularFile(p)) {
					ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
					ex.getResponseHeaders().add("Content-Disposition",
							"attachment; filename=\"" + p.getFileName().toString().replace("\"", "") + "\"");
					ex.sendResponseHeaders(200, Files.size(p));
					try (OutputStream o = ex.getResponseBody()) {
						Files.copy(p, o);
					}
				} else {
					send(ex, 404, "text/plain", "Not found");
				}
			}
			case "PUT" -> {
				if (p.equals(workspace) || Files.isDirectory(p)) {
					send(ex, 400, "text/plain", "Give a file name");
					return;
				}
				Files.createDirectories(p.getParent());
				Path tmp = Files.createTempFile(p.getParent(), ".upload-", ".tmp");
				try {
					try (InputStream in = ex.getRequestBody(); OutputStream o = Files.newOutputStream(tmp)) {
						long n = 0;
						byte[] buf = new byte[65536];
						int r;
						while ((r = in.read(buf)) > 0) {
							n += r;
							if (n > MAX_UPLOAD) {
								throw new IOException("too large");
							}
							o.write(buf, 0, r);
						}
					}
					Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					Files.deleteIfExists(tmp);
					send(ex, 413, "text/plain", "Upload failed: " + e.getMessage());
					return;
				}
				Log.info("uploaded " + workspace.relativize(p));
				send(ex, 201, "text/plain", workspace.relativize(p).toString().replace('\\', '/'));
			}
			default -> {
				ex.getResponseHeaders().add("Allow", "GET, PUT");
				send(ex, 405, "text/plain", "Use GET or PUT");
			}
		}
	}

	/** Listing of a workspace folder with download links and an upload box. */
	String page(Path dir) throws IOException {
		String base = "/files" + prefix() + "/";
		String relDir = workspace.relativize(dir).toString().replace('\\', '/');
		String here = relDir.isEmpty() ? "" : relDir + "/";
		StringBuilder rows = new StringBuilder();
		if (!relDir.isEmpty()) {
			String up = relDir.contains("/") ? relDir.substring(0, relDir.lastIndexOf('/')) + "/" : "";
			rows.append("<tr><td><a href=\"").append(base).append(url(up)).append("\">..</a></td><td></td><td></td></tr>");
		}
		List<Path> entries = new ArrayList<>();
		try (Stream<Path> s = Files.list(dir)) {
			s.filter(q -> !q.getFileName().toString().startsWith(".")).sorted((a, b) -> {
				int d = Boolean.compare(!Files.isDirectory(a), !Files.isDirectory(b));
				return d != 0 ? d : a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
			}).limit(2000).forEach(entries::add);
		}
		for (Path q : entries) {
			String name = q.getFileName().toString();
			boolean d = Files.isDirectory(q);
			rows.append("<tr><td><a href=\"").append(base).append(url(here + name)).append(d ? "/" : "").append("\">")
					.append(Xml.esc(name)).append(d ? "/" : "").append("</a></td><td>")
					.append(d ? "" : size(Files.size(q))).append("</td><td>")
					.append(Instant.ofEpochMilli(Files.getLastModifiedTime(q).toMillis()).toString().replace('T', ' ').substring(0, 16))
					.append("</td></tr>");
		}
		return """
				<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
				<title>RocketMaxxing workspace</title><style>
				:root{color-scheme:light dark;--fg:#1d1d1f;--bg:#fff;--mute:#6e6e73;--line:#e5e5ea;--acc:#0a66d8}
				@media (prefers-color-scheme:dark){:root{--fg:#f5f5f7;--bg:#161618;--mute:#a1a1a6;--line:#2c2c2e;--acc:#5ea2ff}}
				body{font:15px/1.5 system-ui,sans-serif;color:var(--fg);background:var(--bg);max-width:860px;margin:0 auto;padding:24px 16px}
				h1{font-size:20px;margin:0 0 4px}p{color:var(--mute);margin:0 0 20px}a{color:var(--acc);text-decoration:none}
				table{width:100%;border-collapse:collapse}td{padding:6px 8px;border-bottom:1px solid var(--line)}td:nth-child(2),td:nth-child(3){color:var(--mute);white-space:nowrap}
				#drop{border:2px dashed var(--line);border-radius:10px;padding:18px;text-align:center;margin:0 0 20px;color:var(--mute)}
				#drop.on{border-color:var(--acc)}</style></head><body>
				<h1>RocketMaxxing workspace</h1><p>/{{HERE}} &middot; files here are what Claude sees; open a design with its path, e.g. <code>{{HERE}}example.ork</code></p>
				<div id="drop">Drop .ork, motor, RASAero or flight-log files here, or <input type="file" id="pick" multiple></div>
				<table>{{ROWS}}</table>
				<script>
				const drop=document.getElementById('drop');
				async function up(files){for(const f of files){drop.textContent='Uploading '+f.name+'...';
				const r=await fetch(location.pathname.replace(/\\/?$/,'/')+encodeURIComponent(f.name),{method:'PUT',body:f});
				if(!r.ok){alert('Upload of '+f.name+' failed: '+await r.text());}}location.reload();}
				document.getElementById('pick').onchange=e=>up(e.target.files);
				drop.ondragover=e=>{e.preventDefault();drop.classList.add('on')};drop.ondragleave=()=>drop.classList.remove('on');
				drop.ondrop=e=>{e.preventDefault();up(e.dataTransfer.files)};
				</script></body></html>
				""".replace("{{HERE}}", Xml.esc(here)).replace("{{ROWS}}", rows);
	}

	private static String url(String rel) {
		StringBuilder s = new StringBuilder();
		for (String seg : rel.split("/", -1)) {
			if (s.length() > 0 || rel.startsWith("/")) {
				s.append('/');
			}
			s.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
		}
		return s.toString();
	}

	private static String size(long b) {
		return b < 1024 ? b + " B" : b < 1 << 20 ? String.format(Locale.ROOT, "%.1f KB", b / 1024.0)
				: String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
	}

	// ------------------------------------------------------------------------------------------- io

	private static byte[] read(InputStream in, long max) throws IOException {
		byte[] b = in.readNBytes((int) max + 1);
		return b.length > max ? null : b;
	}

	private void sendJson(HttpExchange ex, int code, JsonElement body) throws IOException {
		send(ex, code, "application/json", gson.toJson(body));
	}

	private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
		if (body == null) {
			ex.sendResponseHeaders(code, -1);
			return;
		}
		byte[] b = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", type);
		ex.sendResponseHeaders(code, b.length);
		try (OutputStream o = ex.getResponseBody()) {
			o.write(b);
		}
	}
}
