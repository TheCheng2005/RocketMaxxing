package io.github.openrocketmcp.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

/** A picture on a part, as OpenRocket's decals: placed, saved inside the .ork, and back when it is opened elsewhere. */
class DecalTest {
	@Test
	void decalTravelsInTheFile(@TempDir Path ours, @TempDir Path theirs, @TempDir Path pics) throws Exception {
		BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < 64; x++) {
			for (int y = 0; y < 32; y++) {
				img.setRGB(x, y, (x < 32 ? Color.RED : Color.BLUE).getRGB());
			}
		}
		Path png = pics.resolve("team logo.png");
		ImageIO.write(img, "png", png.toFile());

		ProjectsTest.Team a = ProjectsTest.team(ours);
		String d = a.call("open_design", "{\"example\":\"A simple model rocket\"}").get("designId").getAsString();
		String tube = a.call("app_design", "{\"designId\":\"" + d + "\"}").getAsJsonObject("tree").getAsJsonArray("children").get(0).getAsJsonObject()
				.getAsJsonArray("children").get(1).getAsJsonObject().get("id").getAsString();
		JsonObject r = a.call("app_decal", "{\"designId\":\"" + d + "\",\"component\":\"" + tube + "\",\"path\":" + ProjectsTest.q(png)
				+ ",\"scaleU\":2,\"rotation\":90,\"edge\":\"sticker\"}");
		JsonObject decal = r.getAsJsonObject("decal");
		assertEquals("STICKER", decal.get("edge").getAsString());
		assertEquals(90, decal.get("rotation").getAsDouble(), 1e-9);
		assertTrue(Files.isRegularFile(Path.of(decal.get("image").getAsString())), "the picture is where the page can show it");
		a.call("app_appearance", "{\"designId\":\"" + d + "\",\"component\":\"" + tube + "\",\"color\":\"\"}");
		assertTrue(a.call("app_component", "{\"designId\":\"" + d + "\",\"component\":\"" + tube + "\"}").has("decal"), "default paint keeps the decal");

		Path ork = ours.resolve("with decal.ork");
		a.call("save_design", "{\"designId\":\"" + d + "\",\"path\":" + ProjectsTest.q(ork) + "}");
		Files.delete(png); // the picture now lives in the design file

		ProjectsTest.Team b = ProjectsTest.team(theirs);
		String e = b.call("open_design", "{\"path\":" + ProjectsTest.q(ork) + "}").get("designId").getAsString();
		JsonObject back = b.call("app_component", "{\"designId\":\"" + e + "\",\"component\":\"" + tube + "\"}").getAsJsonObject("decal");
		assertTrue(back != null && back.has("image"), "decal back with its picture: " + back);
		BufferedImage read = ImageIO.read(Path.of(back.get("image").getAsString()).toFile());
		assertEquals(64, read.getWidth());
		assertEquals(2, back.get("scaleU").getAsDouble(), 1e-9);

		b.call("app_decal", "{\"designId\":\"" + e + "\",\"component\":\"" + tube + "\",\"remove\":true}");
		JsonObject gone = b.call("app_component", "{\"designId\":\"" + e + "\",\"component\":\"" + tube + "\"}");
		assertTrue(!gone.has("decal") || gone.get("decal").isJsonNull(), gone.toString());
	}
}
