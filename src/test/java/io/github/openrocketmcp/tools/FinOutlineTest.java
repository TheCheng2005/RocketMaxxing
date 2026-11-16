package io.github.openrocketmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;

import info.openrocket.core.rocketcomponent.FreeformFinSet;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.util.Coordinate;
import io.github.openrocketmcp.Main;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.standards.Standards;

/** Fin outlines from a CSV of points or a picture of the fin, as OpenRocket's freeform fin import. */
class FinOutlineTest {
	static String q(Object o) {
		return new Gson().toJson(o.toString());
	}

	@Test
	void importsPointsAndPictures(@TempDir Path ws) throws Exception {
		Context ctx = new Context(Standards.defaults(), ws, false);
		McpServer s = Main.build(ctx, x -> {
		}, false);
		TemplatesTest.call(s, "open_design", "{\"example\":\"A simple model rocket\"}", false);
		Rocket r = ctx.designs.get(null).doc.getRocket();
		String fins = null;
		for (var c : r) {
			if (c instanceof info.openrocket.core.rocketcomponent.FinSet && fins == null) {
				fins = io.github.openrocketmcp.or.Components.shortId(c);
			}
		}

		// A CSV in inches, drawn from the trailing edge, with a heading.
		Path csv = ws.resolve("fin.csv");
		Files.writeString(csv, "X (in), Y (in)\n4, 0\n3.5, 2.5\n1.5, 2.5\n0, 0\n");
		TemplatesTest.call(s, "import_fin_outline", "{\"component\":\"" + fins + "\",\"path\":" + q(csv) + "}", false);
		FreeformFinSet ff = TemplatesTest.first(r);
		Coordinate[] p = ff.getFinPoints();
		assertEquals(0, p[0].x, 1e-9);
		assertEquals(4 * 0.0254, p[p.length - 1].x, 1e-6, "root chord in inches");
		assertEquals(2.5 * 0.0254, p[1].y, 1e-6);

		// A spreadsheet's CSV with decimal commas and semicolons, in mm.
		Path eu = ws.resolve("fin-eu.csv");
		Files.writeString(eu, "x (mm);y (mm)\n0;0\n37,5;62,5\n87,5;62,5\n100;0\n");
		TemplatesTest.call(s, "import_fin_outline", "{\"component\":\"" + fins + "\",\"path\":" + q(eu) + "}", false);
		p = TemplatesTest.first(r).getFinPoints();
		assertEquals(0.0375, p[1].x, 1e-9);
		assertEquals(0.0625, p[1].y, 1e-9);
		assertEquals(0.1, p[p.length - 1].x, 1e-9);

		// Round trip through a CSV export.
		Path out = ws.resolve("out.csv");
		TemplatesTest.call(s, "export_fin_outline", "{\"component\":\"" + fins + "\",\"path\":" + q(out) + "}", false);
		assertTrue(Files.readString(out).startsWith("x (mm),y (mm)"));

		// A picture: a swept trapezoid, root 400 px, tip 200 px starting 150 px back, span 250 px, root along the bottom.
		BufferedImage img = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, 600, 400);
		g.setColor(new Color(30, 30, 30));
		g.fillPolygon(new int[] { 100, 500, 450, 250 }, new int[] { 350, 350, 100, 100 }, 4);
		g.setColor(Color.WHITE);
		g.fillOval(270, 220, 40, 40); // a lightening hole: only the outer edge counts
		g.dispose();
		Path png = ws.resolve("fin.png");
		ImageIO.write(img, "png", png.toFile());
		TemplatesTest.call(s, "import_fin_outline", "{\"component\":\"" + fins + "\",\"path\":" + q(png) + ",\"rootChord\":\"100 mm\"}", false);
		ff = TemplatesTest.first(r);
		p = ff.getFinPoints();
		double span = 0;
		for (Coordinate c : p) {
			span = Math.max(span, c.y);
		}
		assertEquals(0.100, p[p.length - 1].x, 0.001, "root scaled to 100 mm");
		assertEquals(0.0625, span, 0.0015, "span 250/400 of the root");
		assertTrue(p.length >= 4 && p.length <= 12, "simplified to the corners: " + p.length);
		boolean tipFront = false;
		for (Coordinate c : p) {
			tipFront |= Math.abs(c.y - span) < 0.001 && Math.abs(c.x - 0.0375) < 0.0015;
		}
		assertTrue(tipFront, "tip leading edge 150/400 of the root back");

		Path bad = ws.resolve("bad.csv");
		Files.writeString(bad, "just words\n1,2\n");
		TemplatesTest.call(s, "import_fin_outline", "{\"component\":\"" + fins + "\",\"path\":" + q(bad) + "}", true);
	}
}
