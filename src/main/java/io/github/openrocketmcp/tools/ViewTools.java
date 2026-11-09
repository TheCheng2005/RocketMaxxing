package io.github.openrocketmcp.tools;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.FlightTrack;
import io.github.openrocketmcp.or.Geometry;
import io.github.openrocketmcp.or.Winds;
import io.github.openrocketmcp.report.Drawing;
import io.github.openrocketmcp.report.FlightAnimation;
import io.github.openrocketmcp.report.Png;
import io.github.openrocketmcp.report.Video;
import io.github.openrocketmcp.report.View3d;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** 3-D pictures of the rocket and animations of its simulated flight. */
public final class ViewTools {
	private ViewTools() {
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("render_3d", "3-D view of the rocket: exploded, cut-away or assembled (PNG)",
				"Render the design in shaded 3-D from OpenRocket's geometry. exploded (default): airframe pieces pulled apart "
						+ "along the axis, fins slid out, and every internal part (couplers, bulkheads, centering rings, motor "
						+ "mount, motor, parachutes, shock cords, altimeters, batteries, charges, ballast) laid out below the "
						+ "piece it goes in, with dashed lines to where it fits. cutaway: the near half of the airframe removed so "
						+ "the parts show in place. assembled: the outside. Every part carries a numbered balloon keyed to a parts "
						+ "list with OpenRocket's masses (weighed values where overridden), as on an assembly drawing. Good for "
						+ "design reviews, build instructions, posters and checking a design built from scratch.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false).str("configuration", DesignTools.CONFIG, false)
						.enumStr("view", "exploded (default), cutaway or assembled.", false, "exploded", "cutaway", "assembled")
						.num("azimuth", "Degrees the view turns toward the nose (default 25; 0 = straight side view).", false)
						.num("elevation", "Degrees the view looks down from above (default 22).", false)
						.integer("width", "Image width in pixels (default 1600, 800 to 4000).", false)
						.str("path", "Output .png path (default \"<design>-<view>.png\").", false).build(),
				true, a -> render(ctx, a)));

		s.tool(new ToolDef("animate_flight", "3-D animation of the simulated flight (GIF / MP4)",
				"Simulate and animate the flight in 3-D: a chase camera follows the rocket (the design's own geometry, rolling "
						+ "as simulated, exhaust flame while the motor burns, a smoke trail), and at each deployment the airframe "
						+ "comes apart at its separation joints and hangs under the canopy as it inflates (drogue and main in "
						+ "their own colours). On screen: flight clock, altitude, velocity, vertical speed, Mach, acceleration, "
						+ "distance from the pad and wind; captions for liftoff, rail clear, Mach 1, max velocity, motor burnout, "
						+ "apogee, each deployment and touchdown; a 3-D trajectory inset, an altitude trace, an event timeline, "
						+ "and a flight summary at the end. Playback is real time through the burn and around apogee and "
						+ "deployments; the coast and descent are sped up to fit the length asked for (the rate is shown). "
						+ "Writes a looping GIF (plays in chat, slides, Discord, GitHub), an H.264 MP4 (through ffmpeg when it is "
						+ "installed, else encoded in Java) and a sheet of stills at the key moments, which is also shown in the "
						+ "chat. A path ending in .mp4 writes the MP4 alone (gif: true adds the GIF). Overrides apply to this run only.",
				SimTools.overrides(SimTools.simSelect(Schema.object()))
						.str("path", "Output .gif or .mp4 path (default \"<design>-flight.gif\"); the other files go beside it.", false)
						.num("duration", "Target length in seconds (default 25, 10 to 120).", false)
						.num("fps", "Frames per second (default 15, 5 to 30).", false)
						.integer("width", "MP4 frame width in pixels (default 1280, 480 to 1920; 16:9).", false)
						.integer("gifWidth", "GIF width in pixels (default 720; larger GIFs grow quickly, about 15 MB at 1280).",
								false)
						.bool("mp4", "Also write an MP4 (default true; with a .mp4 path, always).", false)
						.bool("gif", "Also write the GIF (default true; false with a .mp4 path).", false)
						.bool("stills", "Also write the key-moment stills sheet (default true).", false).build(),
				false, a -> animate(ctx, a)));
	}

	private static Object render(Context ctx, Args a) throws Exception {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		FlightConfiguration fc = Components.config(d.doc.getRocket(), a.str("configuration", null));
		View3d.Mode mode;
		try {
			mode = View3d.Mode.of(a.str("view", "exploded"));
		} catch (IllegalArgumentException e) {
			throw new ToolException("view must be exploded, cutaway or assembled.");
		}
		int width = Math.max(800, Math.min(4000, a.integer("width", 1600)));
		String name = mode.name().toLowerCase(Locale.ROOT);
		Path p = ctx.path(a.str("path", Geometry.safe(d.name()) + "-" + name + ".png")).toAbsolutePath();
		if (p.getParent() != null) {
			Files.createDirectories(p.getParent());
		}
		View3d.Result r = View3d.render(fc, Drawing.title(d.name(), fc), mode, Math.max(-180, Math.min(180, a.num("azimuth", 25))),
				Math.max(-85, Math.min(85, a.num("elevation", 22))), width);
		ImageIO.write(r.image(), "png", p.toFile());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("file", p.toString());
		out.put("view", name);
		out.put("shown", Png.attach(r.image(), "3-D " + name + " view of the rocket") ? "the picture is attached as an image"
				: "open the file to see the picture");
		out.put("parts", r.parts());
		return out;
	}

	private static Object animate(Context ctx, Args a) throws Exception {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		Simulation sim = SimTools.runSelected(ctx, a);
		FlightConfiguration fc = sim.getActiveConfiguration();
		FlightTrack.Flight flight = FlightTrack.of(sim);
		double duration = Math.max(10, Math.min(120, a.num("duration", 25)));
		double fps = Math.max(5, Math.min(30, a.num("fps", 15)));
		int width = Math.max(480, Math.min(1920, a.integer("width", 1280)));
		List<String> motors = new ArrayList<>();
		for (MotorConfiguration mc : fc.getActiveMotors()) {
			if (mc.getMotor() != null) {
				motors.add(mc.getMotor().getDesignation());
			}
		}
		String sub = String.join(" + ", motors) + "  ·  wind " + Units.fmt(Winds.speed(sim.getOptions()), Dim.VELOCITY)
				+ "  ·  OpenRocket simulation";
		FlightAnimation anim = new FlightAnimation(flight, fc, sim.getOptions().getLaunchRodLength(),
				new FlightAnimation.Options(width, fps, duration, d.name(), sub));
		Path target = ctx.path(a.str("path", Geometry.safe(d.name()) + "-flight.gif")).toAbsolutePath();
		String fileName = target.getFileName().toString().toLowerCase(Locale.ROOT);
		boolean mp4Path = fileName.endsWith(".mp4");
		if (!mp4Path && !fileName.endsWith(".gif")) {
			throw new ToolException("path must end in .gif or .mp4 (the other files are written beside it).");
		}
		if (target.getParent() != null) {
			Files.createDirectories(target.getParent());
		}
		String stem = target.getFileName().toString().replaceAll("(?i)\\.(gif|mp4)$", "");
		Path gif = a.bool("gif", !mp4Path) ? target.resolveSibling(stem + ".gif") : null;
		Path mp4 = mp4Path || a.bool("mp4", true) ? target.resolveSibling(stem + ".mp4") : null;
		if (gif == null && mp4 == null) {
			throw new ToolException("Nothing to write: give gif or mp4 true.");
		}
		int gifWidth = Math.max(320, Math.min(width, a.integer("gifWidth", 720)));
		Video.Written v = Video.write(anim, gif, gifWidth, mp4);
		Map<String, Object> out = new LinkedHashMap<>();
		if (gif != null) {
			out.put("gif", gif + String.format(Locale.ROOT, " (%.1f MB)", v.gifBytes() / 1e6));
		}
		if (v.mp4() != null) {
			out.put("mp4", v.mp4() + String.format(Locale.ROOT, " (%.1f MB)", v.mp4Bytes() / 1e6));
		} else if (v.mp4Note() != null) {
			out.put("mp4", v.mp4Note());
		}
		out.put("length", String.format(Locale.ROOT, "%.1f s, %d frames at %.0f fps, %d x %d", v.seconds(), v.frames(), fps, anim.w,
				anim.h));
		if (a.bool("stills", true)) {
			Path sheet = target.resolveSibling(stem + "-keyframes.png");
			BufferedImage img = anim.contactSheet(1500);
			ImageIO.write(img, "png", sheet.toFile());
			out.put("stills", sheet.toString());
			out.put("shown", Png.attach(img, "Key moments of the simulated flight") ? "the key-moment stills are attached as an image"
					: "open the stills file to see them");
		}
		out.put("timeline", anim.timeline());
		out.put("note", "Playback is real time through the burn and slowed around apogee and each deployment; coast and descent "
				+ "are sped up (the rate is on screen). Flight numbers are OpenRocket's simulation; the hanging pose under the "
				+ "canopy is illustrative.");
		return out;
	}
}
