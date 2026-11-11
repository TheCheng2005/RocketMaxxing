package io.github.openrocketmcp.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.models.beta.messages.BetaBase64ImageSource;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Units;

/**
 * The app's optional assistant, working on the open design through the same tools Claude uses over MCP (so every change
 * it makes can be undone like any other). Off until the team turns it on. It connects one of three ways:
 * <ul>
 * <li>{@code api}: an Anthropic API key, kept in the user's home folder (never in the designs folder, which teams share)
 * or read from ANTHROPIC_API_KEY; the app runs the conversation itself;</li>
 * <li>{@code claude-code}: the user's own Claude Code, signed in with their Claude plan;</li>
 * <li>{@code codex}: the user's own Codex, signed in with their ChatGPT plan.</li>
 * </ul>
 * For the last two the app opens a private MCP endpoint over the same open designs and runs the tool headless on each
 * message. The conversation runs in the background and the page polls it for what happened.
 */
final class Assistant {
	/** The models offered, most capable first. */
	static final List<String> MODELS = List.of("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5");
	static final List<String> PROVIDERS = List.of("api", "claude-code", "codex");
	/** Claude Code's model aliases ("" is the user's own default in Claude Code). */
	static final List<String> CLAUDE_CODE_MODELS = List.of("", "opus", "sonnet", "haiku");
	static final int MAX_STEPS = 40;
	static final int MAX_RESULT_CHARS = 60_000;

	private static final Gson GSON = new Gson();

	private final McpServer server;
	private final io.github.openrocketmcp.tools.Context ctx;
	private final Path settingsFile;
	private boolean enabled;
	private String provider = "api";
	private String model = MODELS.get(0);
	private String claudeCodeModel = "";
	private String codexModel = "";
	private String savedKey;
	/** The Claude Code session or Codex thread this conversation continues. */
	private String session;
	private Process process;
	private io.github.openrocketmcp.mcp.HttpTransport endpoint;
	private String endpointUrl, endpointToken;
	private String lastState;

	private final List<BetaMessageParam> history = new ArrayList<>();
	private final List<Map<String, Object>> events = new ArrayList<>();
	private volatile boolean running;
	private volatile boolean stopAsked;
	private List<BetaTool> tools;
	private int rpcId;
	private int turnStart;
	private AnthropicClient client;
	private String clientKey;

	Assistant(McpServer server, io.github.openrocketmcp.tools.Context ctx, Path settingsFile) {
		this.server = server;
		this.ctx = ctx;
		this.settingsFile = settingsFile;
		load();
	}

	static Path defaultSettings() {
		return Path.of(System.getProperty("user.home"), ".rocketmaxxing", "assistant.json");
	}

	// ------------------------------------------------------------------------------------------- settings

	private void load() {
		try {
			if (Files.isRegularFile(settingsFile)) {
				JsonObject o = JsonParser.parseString(Files.readString(settingsFile)).getAsJsonObject();
				enabled = o.has("enabled") && o.get("enabled").getAsBoolean();
				if (o.has("model") && MODELS.contains(o.get("model").getAsString())) {
					model = o.get("model").getAsString();
				}
				savedKey = o.has("key") ? o.get("key").getAsString() : null;
				if (o.has("provider") && PROVIDERS.contains(o.get("provider").getAsString())) {
					provider = o.get("provider").getAsString();
				}
				if (o.has("claudeCodeModel") && CLAUDE_CODE_MODELS.contains(o.get("claudeCodeModel").getAsString())) {
					claudeCodeModel = o.get("claudeCodeModel").getAsString();
				}
				codexModel = o.has("codexModel") ? o.get("codexModel").getAsString() : "";
			}
		} catch (IOException | RuntimeException e) {
			// unreadable settings: start with the assistant off
		}
	}

	private void save() throws IOException {
		JsonObject o = new JsonObject();
		o.addProperty("enabled", enabled);
		o.addProperty("provider", provider);
		o.addProperty("model", model);
		o.addProperty("claudeCodeModel", claudeCodeModel);
		o.addProperty("codexModel", codexModel);
		if (savedKey != null) {
			o.addProperty("key", savedKey);
		}
		Files.createDirectories(settingsFile.getParent());
		Files.writeString(settingsFile, o.toString());
		try {
			Files.setPosixFilePermissions(settingsFile, PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException | IOException notPosix) {
			// Windows: the file sits in the user's own profile
		}
	}

	private String key() {
		if (savedKey != null && !savedKey.isBlank()) {
			return savedKey;
		}
		String env = System.getenv("ANTHROPIC_API_KEY");
		return env == null || env.isBlank() ? null : env;
	}

	synchronized Map<String, Object> settings() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("enabled", enabled);
		out.put("provider", provider);
		out.put("model", model);
		out.put("models", MODELS);
		out.put("claudeCodeModel", claudeCodeModel);
		out.put("claudeCodeModels", CLAUDE_CODE_MODELS);
		out.put("codexModel", codexModel);
		out.put("hasKey", key() != null);
		out.put("keySource", savedKey != null ? "saved" : key() != null ? "environment" : null);
		out.put("settingsFile", settingsFile.toString());
		return out;
	}

	/** Whether Claude Code and Codex are installed and signed in (their own commands; a few seconds). */
	static List<Map<String, Object>> connections() {
		// Each check starts the program twice (version, sign-in): the two programs are asked side by side.
		var codex = java.util.concurrent.CompletableFuture.supplyAsync(() -> AgentCli.status(AgentCli.Kind.CODEX));
		return List.of(AgentCli.status(AgentCli.Kind.CLAUDE_CODE), codex.join());
	}

	synchronized Map<String, Object> configure(Boolean on, String newProvider, String newModel, String newKey, boolean forgetKey,
			String newClaudeCodeModel, String newCodexModel) throws IOException {
		if (on != null) {
			enabled = on;
		}
		if (newProvider != null && !newProvider.equals(provider)) {
			if (!PROVIDERS.contains(newProvider)) {
				throw new ToolException("Connection is one of " + PROVIDERS + ".");
			}
			if (running) {
				throw new ToolException("Wait for the answer (or stop it) before switching.");
			}
			provider = newProvider;
			history.clear();
			session = null;
			if (!events.isEmpty()) {
				event("note", "Now connected through " + label(provider) + "; this starts a new conversation.", null);
			}
		}
		if (newClaudeCodeModel != null) {
			if (!CLAUDE_CODE_MODELS.contains(newClaudeCodeModel)) {
				throw new ToolException("Claude Code model is one of " + CLAUDE_CODE_MODELS + ".");
			}
			claudeCodeModel = newClaudeCodeModel;
		}
		if (newCodexModel != null) {
			if (!newCodexModel.isBlank() && !newCodexModel.trim().matches("[A-Za-z0-9._:-]{1,64}")) {
				throw new ToolException("A Codex model name is letters, digits, dots and dashes.");
			}
			codexModel = newCodexModel.trim();
		}
		if (newModel != null) {
			if (!MODELS.contains(newModel)) {
				throw new ToolException("Model is one of " + MODELS + ".");
			}
			model = newModel;
		}
		if (newKey != null && !newKey.isBlank()) {
			savedKey = newKey.trim();
		}
		if (forgetKey) {
			savedKey = null;
		}
		save();
		return settings();
	}

	// ------------------------------------------------------------------------------------------- conversation

	synchronized Map<String, Object> poll(int from) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("running", running);
		out.put("events", new ArrayList<>(events.subList(Math.max(0, Math.min(from, events.size())), events.size())));
		out.put("next", events.size());
		return out;
	}

	synchronized void reset() {
		if (running) {
			throw new ToolException("Wait for the answer (or stop it) before starting a new conversation.");
		}
		history.clear();
		events.clear();
		session = null;
	}

	void stop() {
		stopAsked = true;
		Process p = process;
		if (p != null) {
			AgentCli.kill(p);
		}
	}

	static String label(String provider) {
		return switch (provider) {
			case "claude-code" -> "Claude Code";
			case "codex" -> "Codex";
			default -> "the Anthropic API";
		};
	}

	/** Starts one turn: the team's message with what the app is showing, then Claude and the tools until it answers. */
	synchronized void send(String text, String context) {
		if (!enabled) {
			throw new ToolException("The assistant is off. Turn it on in Settings.");
		}
		if (provider.equals("api") && key() == null) {
			throw new ToolException("No API key. Add one in Settings (or set ANTHROPIC_API_KEY).");
		}
		if (running) {
			throw new ToolException("Still working on the last message.");
		}
		if (text == null || text.isBlank()) {
			throw new ToolException("Type a message first.");
		}
		AgentCli.Kind kind = AgentCli.Kind.of(provider);
		if (kind != null) {
			Path exe = AgentCli.find(kind);
			if (exe == null) {
				throw ToolException.system(kind.label + " is not installed on this computer (see Settings > Assistant).");
			}
			event("you", text, null);
			running = true;
			stopAsked = false;
			String[] ep = endpoint();
			String model = kind == AgentCli.Kind.CLAUDE_CODE ? claudeCodeModel : codexModel;
			AgentCli.Turn turn = new AgentCli.Turn(kind, model, session, ep[0], ep[1], workspace(), system(),
					(context == null ? "" : "[" + context + "]\n\n") + text);
			Thread t = new Thread(() -> runCli(turn, exe), "assistant");
			t.setDaemon(true);
			t.start();
			return;
		}
		event("you", text, null);
		turnStart = history.size();
		history.add(BetaMessageParam.ofUser((context == null ? "" : "[" + context + "]\n\n") + text));
		running = true;
		stopAsked = false;
		String k = key(), m = model;
		Thread t = new Thread(() -> run(k, m), "assistant");
		t.setDaemon(true);
		t.start();
	}

	private void run(String key, String model) {
		try {
			AnthropicClient client = client(key);
			for (int step = 0; step < MAX_STEPS; step++) {
				if (stopAsked) {
					event("note", "Stopped.", null);
					return;
				}
				BetaMessage reply = client.beta().messages().create(request(model));
				synchronized (this) {
					history.add(reply.toParam()); // unchanged, thinking blocks included
				}
				List<BetaToolUseBlock> calls = new ArrayList<>();
				for (BetaContentBlock b : reply.content()) {
					b.text().ifPresent(t -> {
						if (!t.text().isBlank()) {
							event("claude", t.text(), null);
						}
					});
					b.toolUse().ifPresent(calls::add);
				}
				BetaStopReason why = reply.stopReason().orElse(BetaStopReason.END_TURN);
				if (why.equals(BetaStopReason.REFUSAL)) {
					event("error", "Claude declined to answer this request.", null);
					return;
				}
				if (why.equals(BetaStopReason.PAUSE_TURN)) {
					continue;
				}
				if (calls.isEmpty()) {
					if (why.equals(BetaStopReason.MAX_TOKENS)) {
						event("note", "The answer was cut off at its length limit.", null);
					}
					return;
				}
				List<BetaContentBlockParam> results = new ArrayList<>();
				for (BetaToolUseBlock call : calls) {
					results.add(BetaContentBlockParam.ofToolResult(callTool(call)));
				}
				synchronized (this) {
					history.add(BetaMessageParam.ofUserOfContentBlockParams(results));
				}
			}
			event("note", "Stopped after " + MAX_STEPS + " steps; ask it to carry on if it was not finished.", null);
		} catch (AnthropicServiceException e) {
			event("error", "The Claude API refused the request (" + e.statusCode() + "): " + e.getMessage(), null);
			rollBack();
		} catch (AnthropicIoException e) {
			event("error", "Could not reach the Claude API: " + e.getMessage(), null);
			rollBack();
		} catch (RuntimeException e) {
			event("error", "The assistant stopped: " + e.getMessage(), null);
			rollBack();
		} finally {
			running = false;
		}
	}

	/** One turn of Claude Code or Codex: the tool runs headless against the app's endpoint and reports as it goes. */
	private void runCli(AgentCli.Turn turn, Path exe) {
		lastState = state();
		try {
			process = AgentCli.start(turn, exe);
			String error = AgentCli.read(turn.kind(), process, new AgentCli.Sink() {
				@Override
				public void text(String text) {
					event("claude", text, null);
				}

				@Override
				public void tool(String name, String input, String result, boolean ok, List<String> images) {
					toolEvent(name, input, result, ok, images);
				}

				@Override
				public void note(String text, boolean error) {
					event(error ? "error" : "note", text, null);
				}

				@Override
				public void session(String id) {
					if (id != null && !id.isBlank()) {
						synchronized (Assistant.this) {
							session = id;
						}
					}
				}
			});
			if (stopAsked) {
				event("note", "Stopped.", null);
			} else if (error != null) {
				event("error", AgentCli.hint(turn.kind(), error.isBlank() ? turn.kind().label + " stopped without an answer." : error), null);
			}
		} catch (IOException | RuntimeException e) {
			event("error", "Could not run " + turn.kind().label + ": " + e.getMessage(), null);
		} finally {
			process = null;
			running = false;
		}
	}

	/** The private MCP endpoint Claude Code and Codex use: the engine's tools over the same open designs, on 127.0.0.1. */
	private synchronized String[] endpoint() {
		if (endpoint == null) {
			McpServer tools = Main.build(ctx, s -> {
			}, true);
			// The same per-design locks as the app's own calls: an agent's edit and one from the page take turns.
			tools.guard(server.guard());
			byte[] b = new byte[24];
			new java.security.SecureRandom().nextBytes(b);
			String token = java.util.HexFormat.of().formatHex(b);
			io.github.openrocketmcp.mcp.HttpTransport t = new io.github.openrocketmcp.mcp.HttpTransport(tools, workspace(), token, List.of());
			try {
				int port = t.start("127.0.0.1", 0, 4).getPort();
				endpoint = t;
				endpointUrl = "http://127.0.0.1:" + port + "/mcp";
				endpointToken = token;
			} catch (IOException e) {
				throw new ToolException("Could not open the tools for " + label(provider) + ": " + e.getMessage());
			}
		}
		return new String[] { endpointUrl, endpointToken };
	}

	private Path workspace() {
		return ctx.workspace() != null ? ctx.workspace() : Path.of("").toAbsolutePath();
	}

	/** A failed turn leaves the history as it was before it, so the next message starts from a clean exchange. */
	private synchronized void rollBack() {
		while (history.size() > turnStart) {
			history.remove(history.size() - 1);
		}
	}

	private synchronized AnthropicClient client(String key) {
		String base = System.getProperty("rocketmaxxing.anthropicBaseUrl"); // tests point it at a local stand-in
		String id = key + " " + base;
		if (client == null || !id.equals(clientKey)) {
			AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().fromEnv().apiKey(key).timeout(Duration.ofMinutes(10));
			if (base != null) {
				b.baseUrl(base);
			}
			client = b.build();
			clientKey = id;
		}
		return client;
	}

	private synchronized MessageCreateParams request(String model) {
		MessageCreateParams.Builder b = MessageCreateParams.builder()
				.model(model)
				.maxTokens(16000L)
				.systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder().text(system())
						.cacheControl(BetaCacheControlEphemeral.builder().build()).build()))
				.outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
				.messages(new ArrayList<>(history));
		for (BetaTool t : tools()) {
			b.addTool(t);
		}
		if (!model.startsWith("claude-haiku")) {
			// A refused request is answered by the fallback model the API picks for that kind of refusal.
			b.addBeta("server-side-fallback-2026-07-01").fallbacksDefault();
		}
		return b.build();
	}

	private static String system() {
		return "You are the assistant inside RocketMaxxing, a desktop app for designing, simulating and checking "
				+ "competition rockets. The team sees the same design you work on: every change you make with the tools "
				+ "shows in the app at once and can be undone with Ctrl+Z. Each message starts with what the app is "
				+ "showing (the open design and its id); use that designId. Keep answers short and concrete, with the "
				+ "numbers that matter; say what you changed. Ask before large changes to the design.\n\n" + Main.INSTRUCTIONS;
	}

	/** The engine's tools as Claude sees them over MCP (the app's own tools stay with the app). */
	private synchronized List<BetaTool> tools() {
		if (tools != null) {
			return tools;
		}
		List<ToolDef> defs = new ArrayList<>();
		for (ToolDef t : server.tools().values()) {
			if (!t.name().startsWith("app_")) {
				defs.add(t);
			}
		}
		defs.sort((x, y) -> x.name().compareTo(y.name())); // a fixed order keeps the cached prefix stable
		List<BetaTool> out = new ArrayList<>();
		for (int i = 0; i < defs.size(); i++) {
			ToolDef t = defs.get(i);
			JsonObject schema = t.inputSchema();
			BetaTool.InputSchema.Builder s = BetaTool.InputSchema.builder();
			if (schema.has("properties")) {
				s.properties(BetaTool.InputSchema.Properties.builder()
						.putAllAdditionalProperties(toJsonValues(schema.getAsJsonObject("properties"))).build());
			}
			if (schema.has("required")) {
				List<String> req = new ArrayList<>();
				schema.getAsJsonArray("required").forEach(e -> req.add(e.getAsString()));
				s.required(req);
			}
			BetaTool.Builder tb = BetaTool.builder().name(t.name()).description(t.description()).inputSchema(s.build());
			if (i == defs.size() - 1) {
				tb.cacheControl(BetaCacheControlEphemeral.builder().build());
			}
			out.add(tb.build());
		}
		tools = out;
		return out;
	}

	private static Map<String, JsonValue> toJsonValues(JsonObject o) {
		Map<String, JsonValue> m = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			m.put(e.getKey(), JsonValue.from(GSON.fromJson(e.getValue(), Object.class)));
		}
		return m;
	}

	/** Runs one tool call through the server, like a chat client would, and turns its result into a tool_result. */
	private BetaToolResultBlockParam callTool(BetaToolUseBlock call) {
		String input;
		try {
			input = ObjectMappers.jsonMapper().writeValueAsString(call._input());
		} catch (Exception e) {
			input = "{}";
		}
		JsonObject req = new JsonObject();
		req.addProperty("jsonrpc", "2.0");
		synchronized (this) {
			req.addProperty("id", ++rpcId);
		}
		req.addProperty("method", "tools/call");
		JsonObject params = new JsonObject();
		params.addProperty("name", call.name());
		params.add("arguments", JsonParser.parseString(input));
		req.add("params", params);
		String before = state();
		JsonObject res = server.handleLine(req.toString());
		List<BetaToolResultBlockParam.Content.Block> blocks = new ArrayList<>();
		List<String> images = new ArrayList<>();
		StringBuilder shown = new StringBuilder();
		boolean error;
		if (res.has("error")) {
			error = true;
			String msg = res.getAsJsonObject("error").get("message").getAsString();
			blocks.add(BetaToolResultBlockParam.Content.Block.ofText(msg));
			shown.append(msg);
		} else {
			JsonObject result = res.getAsJsonObject("result");
			error = result.has("isError") && result.get("isError").getAsBoolean();
			for (JsonElement c : result.getAsJsonArray("content")) {
				JsonObject item = c.getAsJsonObject();
				String type = item.get("type").getAsString();
				if (type.equals("text")) {
					String text = item.get("text").getAsString();
					if (text.length() > MAX_RESULT_CHARS) {
						text = text.substring(0, MAX_RESULT_CHARS) + "\n[cut: " + text.length() + " characters in all]";
					}
					blocks.add(BetaToolResultBlockParam.Content.Block.ofText(text));
					shown.append(text);
				} else if (type.equals("image") && item.has("data")) {
					String mime = item.has("mimeType") ? item.get("mimeType").getAsString() : "image/png";
					String data = item.get("data").getAsString();
					blocks.add(BetaToolResultBlockParam.Content.Block.ofImage(BetaBase64ImageSource.builder()
							.data(data).mediaType(BetaBase64ImageSource.MediaType.of(mime)).build()));
					images.add("data:" + mime + ";base64," + data);
				}
			}
		}
		lastState = before;
		toolEvent(call.name(), input, shown.toString(), !error, images);
		return BetaToolResultBlockParam.builder().toolUseId(call.id()).contentOfBlocks(blocks).isError(error).build();
	}

	/** One tool call for the page: what was called, with what, what came back, and whether the design changed. */
	private void toolEvent(String name, String input, String result, boolean ok, List<String> images) {
		ToolDef def = server.tools().get(name);
		String now = state();
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("tool", name);
		e.put("title", def == null ? name : def.title());
		e.put("input", input.length() > 400 ? input.substring(0, 400) + "..." : input);
		e.put("ok", ok);
		e.put("changed", lastState != null && !now.equals(lastState));
		e.put("result", result.length() > 1500 ? result.substring(0, 1500) + "..." : result);
		if (!images.isEmpty()) {
			e.put("images", images);
		}
		lastState = now;
		event("tool", null, e);
	}

	/** Which designs are open and each one's modification count: a tool changed something when this changes. */
	private String state() {
		StringBuilder b = new StringBuilder();
		for (io.github.openrocketmcp.or.Designs.Design d : ctx.designs.all()) {
			b.append(d.id).append(':').append(d.doc.getRocket().getModID()).append(':').append(d.doc.getSimulations().size()).append(' ');
		}
		return b.toString();
	}

	private synchronized void event(String kind, String text, Map<String, Object> extra) {
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("kind", kind);
		if (text != null) {
			e.put("text", text);
		}
		if (extra != null) {
			e.putAll(extra);
		}
		events.add(e);
	}

	/** One line of what the app shows, sent with each message so Claude works on the same design. */
	static String context(io.github.openrocketmcp.or.Designs.Design d) {
		if (d == null) {
			return "No design open; units " + Units.system().name().toLowerCase();
		}
		var r = d.doc.getRocket();
		return "Open design: " + d.name() + " (designId " + d.id + "), configuration "
				+ r.getSelectedConfiguration().getName() + "; units " + Units.system().name().toLowerCase();
	}
}
