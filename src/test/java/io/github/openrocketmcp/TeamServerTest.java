package io.github.openrocketmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.HttpTransport;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.Context;
import io.github.openrocketmcp.tools.DesignLocks;

/** The shared team server: MCP over HTTP, token, origin checks, the workspace sandbox and the files page. */
class TeamServerTest {
	static final String TOKEN = "0123456789abcdef0123456789abcdef";
	static Path ws;
	static HttpTransport transport;
	static String base;
	static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

	@BeforeAll
	static void start() throws Exception {
		ws = Files.createTempDirectory("openrocket-ws-");
		Context ctx = new Context(Standards.defaults(), ws, true);
		McpServer server = Main.build(ctx);
		server.guard(new DesignLocks(ctx));
		server.outputFilter(Main.relativePaths(ctx.workspace()));
		transport = new HttpTransport(server, ws, TOKEN, List.of());
		int port = transport.start("127.0.0.1", 0, 8).getPort();
		base = "http://127.0.0.1:" + port;
		// A design in the workspace, as someone would upload it.
		try (var in = TeamServerTest.class.getResourceAsStream("/datafiles/examples/A simple model rocket.ork")) {
			Files.createDirectories(ws.resolve("designs"));
			Files.copy(in, ws.resolve("designs/simple.ork"));
		}
	}

	@AfterAll
	static void stop() {
		transport.stop();
	}

	static HttpResponse<String> post(String path, String body, String... headers) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.ofString(body))
				.header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
		for (int i = 0; i < headers.length; i += 2) {
			b.header(headers[i], headers[i + 1]);
		}
		return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	static String call(String tool, String args) throws Exception {
		HttpResponse<String> r = post("/mcp/" + TOKEN, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\""
				+ tool + "\",\"arguments\":" + args + "}}");
		assertEquals(200, r.statusCode(), r.body());
		JsonObject res = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("result");
		String text = res.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		return (res.get("isError").getAsBoolean() ? "ERROR " : "") + text;
	}

	@Test
	void toolCallWithAProgressTokenStreamsProgressThenTheResult() throws Exception {
		String open = call("open_design", "{\"example\":\"Dual parachute\"}");
		String id = JsonParser.parseString(open).getAsJsonObject().get("designId").getAsString();
		HttpResponse<String> r = post("/mcp/" + TOKEN, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":"
				+ "\"monte_carlo\",\"arguments\":{\"designId\":\"" + id + "\",\"runs\":8},\"_meta\":{\"progressToken\":\"p9\"}}}");
		assertEquals(200, r.statusCode());
		assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
		List<JsonObject> events = new ArrayList<>();
		for (String line : r.body().split("\n")) {
			if (line.startsWith("data: ")) {
				events.add(JsonParser.parseString(line.substring(6)).getAsJsonObject());
			}
		}
		assertTrue(events.size() >= 2, r.body());
		assertEquals("notifications/progress", events.get(0).get("method").getAsString());
		JsonObject last = events.get(events.size() - 1);
		assertEquals(9, last.get("id").getAsInt(), "the result comes last on the same stream");
		assertFalse(last.getAsJsonObject("result").get("isError").getAsBoolean());
		call("close_design", "{\"designId\":\"" + id + "\"}");
	}

	@Test
	void speaksMcpOverHttpWithTheToken() throws Exception {
		String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}";
		assertEquals(401, post("/mcp", init).statusCode());
		assertEquals(401, post("/mcp", init, "Authorization", "Bearer wrong-token-0000000000").statusCode());
		assertEquals(404, post("/mcp/not-the-token", init).statusCode());
		HttpResponse<String> ok = post("/mcp", init, "Authorization", "Bearer " + TOKEN);
		assertEquals(200, ok.statusCode());
		assertTrue(ok.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
		JsonObject result = JsonParser.parseString(ok.body()).getAsJsonObject().getAsJsonObject("result");
		assertEquals("2025-06-18", result.get("protocolVersion").getAsString());
		assertTrue(result.get("instructions").getAsString().contains("list_files"), "team instructions");
		assertEquals(200, post("/mcp/" + TOKEN, init).statusCode(), "token in the URL (claude.ai connectors)");
		// Notifications get 202 and no body; the server offers no GET stream.
		assertEquals(202, post("/mcp/" + TOKEN, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").statusCode());
		assertEquals(405, HTTP.send(HttpRequest.newBuilder(URI.create(base + "/mcp/" + TOKEN)).GET().build(),
				HttpResponse.BodyHandlers.ofString()).statusCode());
		// Batches (2025-03-26) and parse errors.
		HttpResponse<String> batch = post("/mcp/" + TOKEN, "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
				+ "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}]");
		assertEquals(2, JsonParser.parseString(batch.body()).getAsJsonArray().size());
		assertEquals(400, post("/mcp/" + TOKEN, "{not json").statusCode());
		assertEquals(200, HTTP.send(HttpRequest.newBuilder(URI.create(base + "/health")).build(),
				HttpResponse.BodyHandlers.ofString()).statusCode());
	}

	@Test
	void browserClientsOnAllowedOriginsGetCors() throws Exception {
		Context ctx = new Context(Standards.defaults(), ws, true);
		HttpTransport t = new HttpTransport(Main.build(ctx), ws, TOKEN, List.of("http://localhost:8080"));
		String b = "http://127.0.0.1:" + t.start("127.0.0.1", 0, 2).getPort();
		try {
			HttpResponse<String> pre = HTTP.send(HttpRequest.newBuilder(URI.create(b + "/mcp")).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
					.header("Origin", "http://localhost:8080").header("Access-Control-Request-Method", "POST").build(),
					HttpResponse.BodyHandlers.ofString());
			assertEquals(204, pre.statusCode());
			assertEquals("http://localhost:8080", pre.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
			assertTrue(pre.headers().firstValue("Access-Control-Allow-Headers").orElse("").contains("authorization"));
			HttpResponse<String> other = HTTP.send(HttpRequest.newBuilder(URI.create(b + "/mcp")).method("OPTIONS",
					HttpRequest.BodyPublishers.noBody()).header("Origin", "http://localhost:3000").build(), HttpResponse.BodyHandlers.ofString());
			assertEquals(403, other.statusCode(), "loopback pages are not given CORS: other local pages cannot call the server");
			assertTrue(other.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
			HttpResponse<String> post = HTTP.send(HttpRequest.newBuilder(URI.create(b + "/mcp/" + TOKEN))
					.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
					.header("Content-Type", "application/json").header("Origin", "http://localhost:8080").build(), HttpResponse.BodyHandlers.ofString());
			assertEquals(200, post.statusCode());
			assertEquals("http://localhost:8080", post.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
		} finally {
			t.stop();
		}
	}

	@Test
	void rejectsForeignBrowserOrigins() throws Exception {
		String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
		assertEquals(403, post("/mcp/" + TOKEN, ping, "Origin", "https://evil.example").statusCode());
		assertEquals(200, post("/mcp/" + TOKEN, ping, "Origin", "http://localhost:3000").statusCode());
	}

	@Test
	void keepsToolsInsideTheWorkspace() throws Exception {
		String outside = call("open_design", "{\"path\":\"/etc/hosts\"}");
		assertTrue(outside.startsWith("ERROR") && outside.contains("workspace"), outside);
		assertTrue(call("open_design", "{\"path\":\"../../etc/hosts\"}").startsWith("ERROR"));
		String files = call("list_files", "{}");
		assertTrue(files.contains("designs/simple.ork"), files);
		assertFalse(files.contains(".openrocket"), files);
		String opened = call("open_design", "{\"path\":\"designs/simple.ork\"}");
		assertTrue(opened.contains("\"file\":\"designs/simple.ork\""), "paths shown relative to the workspace: " + opened);
		// Windows paths come out the same way (backslashes are doubled inside JSON strings).
		var win = Main.relativePaths("C:\\Rockets", "\\");
		assertEquals("{\"file\":\"designs/v2/rocket.ork\"}", win.apply("{\"file\":\"C:\\\\Rockets\\\\designs\\\\v2\\\\rocket.ork\"}"));
		assertEquals("saved designs/rocket.ork", win.apply("saved C:\\Rockets\\designs\\rocket.ork"));
		assertEquals("C:\\Other\\x.ork", win.apply("C:\\Other\\x.ork"));
		assertFalse(opened.contains(ws.toString()), opened);
	}

	@Test
	void uploadsAndDownloadsWorkspaceFiles() throws Exception {
		String url = base + "/files/" + TOKEN + "/logs/flight%201.csv";
		HttpResponse<String> up = HTTP.send(HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofString("t,alt\n0,0\n"))
				.build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(201, up.statusCode(), up.body());
		assertEquals("t,alt\n0,0\n", Files.readString(ws.resolve("logs/flight 1.csv")));
		HttpResponse<String> down = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, down.statusCode());
		assertEquals("t,alt\n0,0\n", down.body());
		assertTrue(down.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment"));
		HttpResponse<String> page = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/files/" + TOKEN + "/")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, page.statusCode());
		assertTrue(page.body().contains("designs/") && page.body().contains("logs/"), page.body());
		for (String bad : List.of("/files/" + TOKEN + "/%2e%2e/%2e%2e/etc/passwd", "/files/" + TOKEN + "/.openrocket-mcp-token",
				"/files/" + TOKEN + "/a/..%2f..%2f..%2fetc%2fpasswd")) {
			int code = HTTP.send(HttpRequest.newBuilder(URI.create(base + bad)).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
			assertTrue(code == 403 || code == 404, bad + " -> " + code);
		}
		assertEquals(404, HTTP.send(HttpRequest.newBuilder(URI.create(base + "/files/")).build(), HttpResponse.BodyHandlers.ofString())
				.statusCode(), "the files page needs the token");
	}

	@Test
	void runsCallsOnTheSameDesignOneAtATime() throws Exception {
		String opened = call("open_design", "{\"example\":\"A simple model rocket\"}");
		String id = JsonParser.parseString(opened).getAsJsonObject().get("designId").getAsString();
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Callable<String>> jobs = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				jobs.add(() -> call("run_simulation", "{\"designId\":\"" + id + "\"}"));
			}
			List<String> apogees = new ArrayList<>();
			for (Future<String> f : pool.invokeAll(jobs)) {
				String r = f.get();
				assertFalse(r.startsWith("ERROR"), r);
				apogees.add(JsonParser.parseString(r).getAsJsonObject().getAsJsonObject("flight").get("apogee").getAsString());
			}
			assertEquals(1, apogees.stream().distinct().count(), "concurrent runs of one design agree: " + apogees);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void locksPerDesign() throws Exception {
		Context c = new Context(Standards.defaults(), ws, true);
		DesignLocks locks = new DesignLocks(c);
		ToolDef t = Main.build(c).tools().get("run_simulation");
		Function<String, Args> args = j -> new Args(
				JsonParser.parseString(j).getAsJsonObject());
		assertEquals(null, locks.lockFor(t, args.apply("{}")), "no design open: nothing to lock");
		var d1 = c.designs.openExample("A simple model rocket");
		var implicit = locks.lockFor(t, args.apply("{}"));
		assertTrue(implicit != null && implicit == locks.lockFor(t, args.apply("{\"designId\":\"" + d1.id + "\"}")),
				"the only open design is locked whether or not the call names it");
		assertEquals(null, locks.lockFor(Main.build(c).tools().get("get_standards"), args.apply("{}")),
				"a tool that takes no design never waits for the only open one");
		var d2 = c.designs.openExample("Dual parachute");
		assertTrue(locks.lockFor(t, args.apply("{\"designId\":\"" + d2.id + "\"}")) != implicit, "other designs run in parallel");
		assertEquals(null, locks.lockFor(t, args.apply("{\"designId\":\"nope\"}")), "unknown ids are reported by the tool");
	}

	@Test
	void generatesAndKeepsAToken() throws Exception {
		Path dir = Files.createTempDirectory("openrocket-token-");
		String t1 = Main.token(List.of("--http"), dir);
		assertTrue(t1.length() >= 32);
		assertEquals(t1, Main.token(List.of("--http"), dir), "same token after a restart");
		assertNotEquals(t1, Main.token(List.of("--http"), Files.createTempDirectory("openrocket-token-")));
		assertThrows(IllegalArgumentException.class, () -> Main.token(List.of("--token", "short"), dir));
		assertThrows(IllegalArgumentException.class, () -> new HttpTransport(Main.build(new Context(Standards.defaults())), dir, null,
				List.of()).start("0.0.0.0", 0, 2), "no token on a public interface");
	}

	@Test
	void contextSandboxRefusesEscapes() throws Exception {
		Context c = new Context(Standards.defaults(), ws, true);
		assertEquals(ws.toAbsolutePath().normalize().resolve("designs/x.ork"), c.path("designs/x.ork"));
		Path abs = ws.getParent().resolve("elsewhere.ork").toAbsolutePath();
		assertThrows(ToolException.class, () -> c.path(abs.toString()));
		assertThrows(ToolException.class, () -> c.path("designs/../../outside.ork"));
		Path link = ws.resolve("escape");
		try {
			Files.createSymbolicLink(link, Path.of(System.getProperty("java.io.tmpdir")));
		} catch (UnsupportedOperationException | IOException e) {
			return; // no symbolic links on this file system (Windows without the privilege)
		}
		assertThrows(ToolException.class, () -> c.path("escape/secret.ork"));
		// Locally (stdio) any path is allowed.
		Context local = new Context(Standards.defaults());
		assertEquals(abs, local.path(abs.toString()));
	}
}
