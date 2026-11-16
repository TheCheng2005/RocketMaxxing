package io.github.openrocketmcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.standards.Standards;

/** Shared server state. */
public final class Context {
	public final Designs designs = new Designs();
	/** Tools used in this session, and features already offered: each suggestion is made once (Suggestions). */
	public final java.util.Set<String> toolsUsed = java.util.concurrent.ConcurrentHashMap.newKeySet();
	public final java.util.Set<String> suggested = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private volatile Standards standards;
	/** Base directory for relative paths; when {@link #sandboxed}, every file must stay inside it (team server). */
	private final Path workspace;
	private final boolean sandboxed;
	/** Where people upload and download workspace files (team server), or null. */
	private volatile String filesUrl;

	public Context(Standards standards) {
		this(standards, null, false);
	}

	public Context(Standards standards, Path workspace, boolean sandboxed) {
		this.standards = standards;
		this.workspace = (workspace == null ? Path.of("") : workspace).toAbsolutePath().normalize();
		this.sandboxed = sandboxed;
	}

	public Standards standards() {
		return standards;
	}

	public void setStandards(Standards s) {
		this.standards = s;
	}

	public String filesUrl() {
		return filesUrl;
	}

	public void setFilesUrl(String url) {
		this.filesUrl = url;
	}

	public Path workspace() {
		return workspace;
	}

	/** Whether this context keeps a journal of what happens (the desktop app does; see {@link Journal}). */
	private volatile boolean journal;

	public boolean journal() {
		return journal;
	}

	public void keepJournal(boolean on) {
		this.journal = on;
	}

	public boolean sandboxed() {
		return sandboxed;
	}

	/**
	 * Resolves a file path from a tool argument: relative paths are relative to the workspace. On a team server
	 * (sandboxed) absolute paths and paths that leave the workspace are refused.
	 */
	public Path path(String p) {
		if (p == null || p.isBlank()) {
			throw new ToolException("A file path is required.");
		}
		Path raw = Path.of(p.trim());
		if (!sandboxed) {
			return (raw.isAbsolute() ? raw : workspace.resolve(raw)).toAbsolutePath().normalize();
		}
		Path r = workspace.resolve(raw).normalize();
		if (!r.startsWith(workspace) || !insideReal(r)) {
			throw new ToolException("On the team server files live in the shared workspace; use a path inside it such as "
					+ "\"designs/rocket.ork\" (see list_files).");
		}
		return r;
	}

	/** The closest existing ancestor must also be inside the workspace once symbolic links are followed. */
	private boolean insideReal(Path r) {
		try {
			Path root = workspace.toRealPath();
			for (Path p = r; p != null; p = p.getParent()) {
				if (Files.exists(p)) {
					return p.toRealPath().startsWith(root);
				}
			}
			return false;
		} catch (IOException e) {
			return false;
		}
	}
}
