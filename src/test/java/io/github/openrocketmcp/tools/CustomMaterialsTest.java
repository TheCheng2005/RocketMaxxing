package io.github.openrocketmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.or.CustomMaterials;
import io.github.openrocketmcp.standards.Standards;

/** The team's own materials: added with a density and unit, kept beside the designs, used by name in parts. */
class CustomMaterialsTest {
	@Test
	void addUseKeepAndRemove(@TempDir Path ws) throws Exception {
		Context ctx = new Context(Standards.defaults(), ws, false);
		McpServer s = Main.build(ctx, x -> {
		}, false);
		TemplatesTest.call(s, "materials", "{\"action\":\"add\",\"type\":\"bulk\",\"name\":\"Team G10 layup\",\"density\":\"1.82 g/cm3\"}", false);
		TemplatesTest.call(s, "materials", "{\"action\":\"add\",\"type\":\"surface\",\"name\":\"Team ripstop\",\"density\":\"40 g/m2\"}", false);
		assertTrue(Files.readString(ws.resolve(CustomMaterials.FILE)).contains("Team G10 layup"));

		TemplatesTest.call(s, "open_design", "{\"example\":\"A simple model rocket\"}", false);
		var r = ctx.designs.get(null).doc.getRocket();
		String tube = null;
		for (RocketComponent c : r) {
			if (c instanceof BodyTube && tube == null) {
				tube = io.github.openrocketmcp.or.Components.shortId(c);
			}
		}
		TemplatesTest.call(s, "edit_components", "{\"changes\":[{\"component\":\"" + tube + "\",\"properties\":{\"material\":\"Team G10 layup\"}}]}", false);
		BodyTube b = (BodyTube) io.github.openrocketmcp.or.Components.find(r, tube);
		assertEquals("Team G10 layup", b.getMaterial().getName());
		assertEquals(1820, b.getMaterial().getDensity(), 1e-6);

		// Another session in the same folder reads them back.
		CustomMaterials.remove(ws, "Team ripstop");
		CustomMaterials.load(ws);
		String list = TemplatesTest.call(s, "materials", "{}", false).toString();
		assertTrue(list.contains("Team G10 layup") && !list.contains("Team ripstop"), list);
		assertEquals(1, list.split("Team G10 layup", -1).length - 1, "listed once, as the kind it is: " + list);

		TemplatesTest.call(s, "materials", "{\"action\":\"add\",\"name\":\"Fiberglass\",\"density\":\"1900\"}", true);
		TemplatesTest.call(s, "materials", "{\"action\":\"add\",\"name\":\"X\",\"density\":\"3 furlongs\"}", true);
		TemplatesTest.call(s, "materials", "{\"action\":\"remove\",\"name\":\"Team G10 layup\"}", false);
		assertFalse(Files.readString(ws.resolve(CustomMaterials.FILE)).contains("Team G10 layup"));
	}
}
