package io.github.openrocketmcp.tools;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.or.Designs;

/**
 * Team server: tool calls on the same design run one at a time (they share its simulations and components); calls
 * on different designs, and calls that touch no design, run in parallel.
 */
public final class DesignLocks implements McpServer.Guard {
	private final Context ctx;
	private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

	public DesignLocks(Context ctx) {
		this.ctx = ctx;
	}

	@Override
	public Lock lockFor(ToolDef tool, Args args) {
		// A tool that takes no design (preferences, standards, searches, the app's own state) never waits for one: a
		// long run on the only open design (a video, a Monte Carlo) must not hold up the rest of the app.
		var schema = tool.inputSchema();
		if (schema == null || !schema.has("properties") || !schema.getAsJsonObject("properties").has("designId")) {
			return null;
		}
		String id = args.str("designId", null);
		if (id == null || id.isBlank()) {
			var open = ctx.designs.all();
			if (open.size() != 1) {
				return null; // the tool touches no design, or it will ask which one
			}
			id = open.get(0).id;
		}
		Designs.Design d;
		try {
			d = ctx.designs.get(id);
		} catch (RuntimeException e) {
			return null; // the tool reports the unknown id
		}
		return locks.computeIfAbsent(d.id, k -> new ReentrantLock(true));
	}
}
