package io.github.openrocketmcp.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.FlightTrack;
import io.github.openrocketmcp.or.Sims;
import io.github.openrocketmcp.standards.Standards;

class FlightAnimationTest {
	static Simulation sim;
	static FlightTrack.Flight flight;

	@BeforeAll
	static void fly() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		sim = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults());
		Sims.run(sim);
		flight = FlightTrack.of(sim);
	}

	static FlightAnimation anim(double duration, double fps, int width) {
		return new FlightAnimation(flight, sim.getActiveConfiguration(), sim.getOptions().getLaunchRodLength(),
				new FlightAnimation.Options(width, fps, duration, "Test", "sub"));
	}

	/** OpenRocket's attitude (theta = elevation, phi = heading from east) points along the flight path in the coast. */
	@Test
	void attitudeFollowsTheFlightPath() {
		FlightTrack.Track m = flight.main();
		double t = 0.5 * (flight.burnout() + flight.apogeeTime());
		double[] ax = m.axis(t), a = m.position(t - 0.05), b = m.position(t + 0.05);
		double[] v = { b[0] - a[0], b[1] - a[1], b[2] - a[2] };
		double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		assertTrue((ax[0] * v[0] + ax[1] * v[1] + ax[2] * v[2]) / l > 0.95, "nose along the velocity");
	}

	@Test
	void eventsAreInOrderAndComplete() {
		double last = -1;
		StringBuilder kinds = new StringBuilder();
		for (FlightTrack.Event e : flight.events()) {
			assertTrue(e.time() >= last);
			last = e.time();
			kinds.append(e.kind()).append(' ');
		}
		for (String k : new String[] { "liftoff", "burnout", "apogee", "deploy", "landing", "maxv" }) {
			assertTrue(kinds.toString().contains(k), k + " in " + kinds);
		}
	}

	@Test
	void playbackIsRealTimeInTheBurnAndFitsTheLength() {
		FlightAnimation a = anim(20, 10, 480);
		assertEquals(1, a.rate(0.5 * flight.burnout()), 1e-9, "real time while the motor burns");
		double fastest = 0;
		for (double t = flight.apogeeTime(); t < flight.landingTime(); t += 0.5) {
			fastest = Math.max(fastest, a.rate(t));
		}
		assertTrue(fastest > 2, "descent sped up: " + fastest);
		double prev = -10;
		for (int i = 0; i < a.frames; i++) {
			double t = a.timeOf(i);
			assertTrue(t >= prev - 1e-9, "time runs forward");
			prev = t;
		}
		assertEquals(flight.main().end(), a.timeOf(a.frames - 1), 1e-6, "ends on the landing");
		assertTrue(a.seconds() > 12 && a.seconds() < 32, "about the length asked for: " + a.seconds());
		assertEquals(a.animOf(flight.apogeeTime()), a.animOf(flight.apogeeTime()), 0);
	}

	@Test
	void writesALoopingGifAndStills(@TempDir Path tmp) throws Exception {
		FlightAnimation a = anim(10, 4, 480);
		BufferedImage f = a.frame(a.frames / 2);
		assertEquals(480, f.getWidth());
		assertEquals(270, f.getHeight());
		Path gif = tmp.resolve("f.gif");
		Video.Written v = Video.write(a, gif, 320, null);
		assertEquals(a.frames, v.frames());
		try (ImageInputStream in = ImageIO.createImageInputStream(gif.toFile())) {
			ImageReader r = ImageIO.getImageReaders(in).next();
			r.setInput(in);
			assertEquals(a.frames, r.getNumImages(true));
			assertEquals(320, r.getWidth(0));
			// The colours a viewer sees are the rendered colours (the file's palette is the one the pixels index).
			for (int i : new int[] { 0, a.frames / 2, a.frames - 1 }) {
				assertTrue(meanColourError(r.read(i), Video.scale(a.frame(i), 320)) < 10, "frame " + i + " colours");
			}
		}
		BufferedImage sheet = a.contactSheet(900);
		assertTrue(sheet.getHeight() > 200 && a.keyMoments().size() >= 4);
		assertTrue(Files.size(gif) > 1000);
	}

	@Test
	void writesAnMp4WithoutFfmpeg(@TempDir Path tmp) throws Exception {
		FlightAnimation a = anim(10, 4, 480);
		Path mp4 = tmp.resolve("f.mp4");
		String before = System.getProperty("openrocketmcp.ffmpeg");
		System.setProperty("openrocketmcp.ffmpeg", "none");
		Video.Written v;
		try {
			v = Video.write(a, null, 320, mp4);
		} finally {
			if (before == null) {
				System.clearProperty("openrocketmcp.ffmpeg");
			} else {
				System.setProperty("openrocketmcp.ffmpeg", before);
			}
		}
		assertEquals(mp4, v.mp4(), "the MP4 is made in Java: " + v.mp4Note());
		assertEquals(null, v.gif(), "MP4 alone");
		assertEquals(Files.size(mp4), v.mp4Bytes());
		// It plays: every frame decodes, at the animation's size, in the rendered colours.
		org.jcodec.api.FrameGrab grab = org.jcodec.api.FrameGrab.createFrameGrab(org.jcodec.common.io.NIOUtils.readableChannel(mp4.toFile()));
		int frames = 0;
		BufferedImage middle = null;
		org.jcodec.common.model.Picture pic;
		while ((pic = grab.getNativeFrame()) != null) {
			if (frames == a.frames / 2) {
				middle = org.jcodec.scale.AWTUtil.toBufferedImage(pic);
			}
			frames++;
		}
		assertEquals(a.frames, frames);
		assertEquals(480, middle.getWidth());
		assertEquals(270, middle.getHeight());
		assertTrue(meanColourError(middle, a.frame(a.frames / 2)) < 12, "H.264 keeps the picture");
	}

	/** A two-stage flight: each stage's events are named, the booster's own recovery is captioned, stills differ. */
	@Test
	void twoStageFlightIsToldStageByStage() throws Exception {
		Designs.Design d = new Designs().openExample("Two stage high power");
		Simulation s = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults());
		Sims.run(s);
		FlightTrack.Flight fl = FlightTrack.of(s);
		List<String> titles = fl.events().stream().map(FlightTrack.Event::title).toList();
		for (String t : new String[] { "Booster burnout", "Booster separation", "Sustainer ignition", "Sustainer burnout",
				"Booster Chute out", "Booster touchdown", "Sustainer touchdown" }) {
			assertTrue(titles.contains(t), t + " in " + titles);
		}
		assertTrue(fl.events().stream().anyMatch(e -> e.kind().equals("landing") && e.branch() == 1), "booster landing on its track");
		FlightTrack.Track booster = fl.tracks().get(1);
		assertTrue(booster.at(booster.cg, booster.separation + 1) > booster.at(booster.cg, 0), "booster CG is aft of the vehicle's");
		FlightAnimation a = new FlightAnimation(fl, s.getActiveConfiguration(), 3, new FlightAnimation.Options(480, 5, 15, "t", ""));
		List<double[]> km = a.keyMoments();
		for (int i = 1; i < km.size(); i++) {
			assertTrue(km.get(i)[0] - km.get(i - 1)[0] > 0.2, "no two stills of the same instant");
		}
		assertEquals(480, a.render(booster.separation + 3, 0).getWidth(), "frame with the booster camera inset");
	}

	/** Staged and clustered rockets: a dropped booster flies its own track; every view renders. */
	@Test
	void stagedAndPodRockets() throws Exception {
		for (String example : new String[] { "Two stage high power", "Parallel booster" }) {
			Designs.Design d = new Designs().openExample(example);
			Simulation s = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults());
			Sims.run(s);
			FlightTrack.Flight fl = FlightTrack.of(s);
			for (int i = 1; i < fl.tracks().size(); i++) {
				// OpenRocket's branch for a dropped stage starts at liftoff; it comes off at its separation event.
				assertTrue(fl.tracks().get(i).separation > 0.1, example + " " + fl.tracks().get(i).name);
			}
			FlightAnimation a = new FlightAnimation(fl, s.getActiveConfiguration(), 2, new FlightAnimation.Options(480, 5, 12, example, ""));
			for (FlightTrack.Event e : fl.events()) {
				assertEquals(480, a.render(e.time() + 0.5, 0).getWidth(), example + " " + e.title());
			}
			assertTrue(a.parallelism() >= 1);
			if (System.getenv("RENDER_SCRATCH") != null) {
				Files.createDirectories(Path.of("build/scratch"));
				ImageIO.write(new FlightAnimation(fl, s.getActiveConfiguration(), 2, new FlightAnimation.Options(1280, 5, 12, example, ""))
						.render(0.3, 0), "png", Path.of("build/scratch/" + example.replace(' ', '_') + "-early.png").toFile());
			}
			for (View3d.Mode m : View3d.Mode.values()) {
				assertTrue(View3d.render(s.getActiveConfiguration(), example, m, 25, 22, 900).parts().size() > 3);
			}
			if (System.getenv("RENDER_SCRATCH") != null) {
				Files.createDirectories(Path.of("build/scratch"));
				for (FlightTrack.Event e : fl.events()) {
					ImageIO.write(new FlightAnimation(fl, s.getActiveConfiguration(), 2, new FlightAnimation.Options(1280, 5, 12, example, ""))
							.render(e.time() + 0.6, 0), "png", Path.of("build/scratch/" + example.replace(' ', '_') + "-" + e.kind() + ".png").toFile());
				}
				ImageIO.write(View3d.render(s.getActiveConfiguration(), example, View3d.Mode.EXPLODED, 25, 22, 1400).image(), "png",
						Path.of("build/scratch/" + example.replace(' ', '_') + "-exploded.png").toFile());
			}
		}
	}

	/** Stages that share a name (two "Booster stage"s) each fly their own track; the lowest comes off first. */
	@Test
	@SuppressWarnings("unchecked")
	void sameNamedStagesEachDropOnTheirOwnTrack() throws Exception {
		Designs.Design d = new Designs().openExample("Three stage");
		Simulation s = Sims.prepare(d, null, null, Sims.Overrides.none(), Standards.defaults());
		Sims.run(s);
		FlightTrack.Flight fl = FlightTrack.of(s);
		FlightAnimation a = new FlightAnimation(fl, s.getActiveConfiguration(), 2, new FlightAnimation.Options(480, 5, 12, "t", ""));
		Map<String, Object> plan = a.plan();
		Map<Integer, Double> separation = new HashMap<>();
		for (Map<String, Object> x : (List<Map<String, Object>>) plan.get("dropped")) {
			separation.put((Integer) x.get("stage"), fl.tracks().get((Integer) x.get("track")).separation);
		}
		assertEquals(Set.of(1, 2), separation.keySet(), "each booster is its own stage");
		assertTrue(separation.get(2) < separation.get(1), "the lowest stage comes off first");
		// The sustainer's sections cover its airframe, not fins sticking out aft of it.
		List<double[]> sections = (List<double[]>) plan.get("sections");
		double end = sections.get(sections.size() - 1)[1];
		for (double[] sec : sections) {
			assertTrue(sec[1] - sec[0] > 0.1 * end, "no sliver section: " + sec[0] + ".." + sec[1]);
		}
	}

	/** Mean absolute difference per colour channel (0-255) between two images of the same size. */
	static double meanColourError(BufferedImage x, BufferedImage y) {
		double sum = 0;
		for (int j = 0; j < x.getHeight(); j++) {
			for (int i = 0; i < x.getWidth(); i++) {
				int p = x.getRGB(i, j), q = y.getRGB(i, j);
				sum += Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255))
						+ Math.abs((p & 255) - (q & 255));
			}
		}
		return sum / (3.0 * x.getWidth() * x.getHeight());
	}
}
