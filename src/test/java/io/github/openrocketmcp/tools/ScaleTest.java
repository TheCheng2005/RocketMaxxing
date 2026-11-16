package io.github.openrocketmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.or.Scale;
import io.github.openrocketmcp.standards.Standards;

/** Scaling a design, as OpenRocket's Edit > Scale: every length by the factor; walls, positions and masses as asked. */
class ScaleTest {
	static <T> T first(Rocket r, Class<T> type) {
		for (RocketComponent c : r) {
			if (type.isInstance(c)) {
				return type.cast(c);
			}
		}
		throw new AssertionError("no " + type.getSimpleName());
	}

	@Test
	void scalesEveryLengthAndUndoesInOne(@TempDir Path ws) {
		Context ctx = new Context(Standards.defaults(), ws, false);
		McpServer s = Main.build(ctx, x -> {
		}, false);
		TemplatesTest.call(s, "open_design", "{\"example\":\"A simple model rocket\"}", false);
		Rocket r = ctx.designs.get(null).doc.getRocket();
		BodyTube tube = first(r, BodyTube.class);
		NoseCone nose = first(r, NoseCone.class);
		TrapezoidFinSet fins = first(r, TrapezoidFinSet.class);
		double d = 2 * tube.getOuterRadius(), wall = tube.getThickness(), len = Scale.length(r), root = fins.getRootChord(), span = fins.getHeight(),
				shoulder = nose.getAftShoulderLength(), fin = fins.getAxialOffset();

		TemplatesTest.call(s, "scale_design", "{\"factor\":2}", false);
		assertEquals(2 * d, 2 * tube.getOuterRadius(), 1e-9);
		assertEquals(2 * wall, tube.getThickness(), 1e-9);
		assertEquals(2 * len, Scale.length(r), 1e-6);
		assertEquals(2 * root, fins.getRootChord(), 1e-9);
		assertEquals(2 * span, fins.getHeight(), 1e-9);
		assertEquals(2 * shoulder, nose.getAftShoulderLength(), 1e-9, "aliases scale once");
		assertEquals(2 * fin, fins.getAxialOffset(), 1e-9);

		TemplatesTest.call(s, "undo", "{}", false);
		tube = first(r, BodyTube.class); // undo puts back copies of the parts
		assertEquals(d, 2 * tube.getOuterRadius(), 1e-9);
		assertEquals(len, Scale.length(r), 1e-6);

		// To a diameter, keeping the tubing wall.
		TemplatesTest.call(s, "scale_design", "{\"diameter\":\"" + (d * 1.5 * 1000) + " mm\",\"thickness\":false}", false);
		tube = first(r, BodyTube.class);
		assertEquals(1.5 * d, 2 * tube.getOuterRadius(), 1e-9);
		assertEquals(wall, tube.getThickness(), 1e-9);

		TemplatesTest.call(s, "scale_design", "{\"factor\":2,\"length\":\"1 m\"}", true);
		TemplatesTest.call(s, "scale_design", "{\"factor\":500}", true);
	}
}
