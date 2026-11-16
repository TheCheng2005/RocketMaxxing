package io.github.openrocketmcp.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.tools.Journal;

/**
 * The History page: edits, undos, saves and changes to the team standards are kept in words with who made them, and a
 * standards change can be put back.
 */
class JournalTest {
	@Test
	void keepsWhatHappenedAndPutsStandardsBack(@TempDir Path ws) throws Exception {
		ProjectsTest.Team t = ProjectsTest.team(ws);
		t.ctx().keepJournal(true);
		String d = t.call("open_design", "{\"example\":\"A simple model rocket\"}").get("designId").getAsString();
		String nose = t.call("app_design", "{\"designId\":\"" + d + "\"}").getAsJsonObject("tree").getAsJsonArray("children").get(0).getAsJsonObject()
				.getAsJsonArray("children").get(0).getAsJsonObject().get("id").getAsString();
		t.call("app_edit", "{\"designId\":\"" + d + "\",\"component\":\"" + nose + "\",\"key\":\"length\",\"value\":\"12 cm\"}");
		Journal.as("Claude", () -> t.call("app_edit", "{\"designId\":\"" + d + "\",\"component\":\"" + nose + "\",\"key\":\"length\",\"value\":\"14 cm\"}"));

		JsonObject h = t.call("app_history", "{\"designId\":\"" + d + "\"}");
		JsonArray undoable = h.getAsJsonArray("undoable");
		assertTrue(undoable.get(0).getAsJsonObject().get("what").getAsString().matches("Set length of .+ to 14 cm"), undoable.toString());
		JsonArray since = h.getAsJsonArray("sinceSave");
		assertEquals(2, since.size(), since.toString());
		assertEquals("Claude", since.get(0).getAsJsonObject().get("by").getAsString(), "the assistant's edit is put down to it");
		assertEquals("you", since.get(1).getAsJsonObject().get("by").getAsString());

		t.call("undo", "{\"designId\":\"" + d + "\",\"steps\":2}");
		JsonObject undid = t.call("app_history", "{\"designId\":\"" + d + "\"}").getAsJsonArray("events").get(0).getAsJsonObject();
		assertEquals("undo", undid.get("kind").getAsString());
		assertTrue(undid.get("what").getAsString().startsWith("Undid: Set length of"), undid.toString());

		t.call("save_design", "{\"designId\":\"" + d + "\",\"path\":" + ProjectsTest.q(ws.resolve("simple.ork")) + "}");
		assertEquals(0, t.call("app_history", "{\"designId\":\"" + d + "\"}").getAsJsonArray("sinceSave").size(), "nothing since the save");

		// A target changed, then put back.
		t.call("app_set_targets", "{\"targets\":{\"apogee\":{\"goal\":\"300 m\"}}}");
		JsonObject change = t.call("app_history", "{}").getAsJsonArray("events").get(0).getAsJsonObject();
		assertEquals("standards", change.get("kind").getAsString());
		assertEquals("Apogee goal set to 300 m", change.get("what").getAsString());
		Path file = ws.resolve(Standards.FILE_NAME);
		assertTrue(Files.readString(file).contains("300 m"));
		// Changed again since: refused unless forced.
		t.call("app_set_targets", "{\"targets\":{\"apogee\":{\"max\":\"400 m\"}}}");
		String id = ProjectsTest.q(change.get("id").getAsString());
		assertTrue(t.call("app_history_revert", "{\"id\":" + id + "}", true).get("error").getAsString().contains("changed again"));
		t.call("app_history_revert", "{\"id\":" + id + ",\"force\":true}");
		assertFalse(Files.readString(file).contains("300 m"), "back to before the goal was set");
		assertFalse(Files.readString(file).contains("400 m"), "forcing takes back the later change too");
		assertTrue(Files.isRegularFile(ws.resolve("history/journal.jsonl")), "kept in the designs folder");
	}

	@Test
	void aPlainServerKeepsNoJournal(@TempDir Path ws) {
		ProjectsTest.Team t = ProjectsTest.team(ws);
		String d = t.call("open_design", "{\"example\":\"A simple model rocket\"}").get("designId").getAsString();
		t.call("targets", "{\"designId\":\"" + d + "\",\"set\":{\"apogee\":{\"min\":\"20 m\"}}}");
		assertFalse(Files.exists(ws.resolve("history")), "nothing written beside the designs");
	}
}
