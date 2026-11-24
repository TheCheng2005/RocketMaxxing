package io.github.openrocketmcp.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.RailButton;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import io.github.openrocketmcp.or.Designs;

/** The 3-D parts: nothing pokes through the skin, every kind of part is drawn, cut-aways open what holds others. */
class Model3dTest {

	static <T extends RocketComponent> T first(Rocket r, Class<T> type) {
		for (RocketComponent c : r) {
			if (type.isInstance(c)) {
				return type.cast(c);
			}
		}
		throw new AssertionError("no " + type.getSimpleName());
	}

	static Model3d.Part part(List<Model3d.Part> parts, String name) {
		return parts.stream().filter(p -> p.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError("no part " + name));
	}

	/** Largest distance of any vertex from the rocket's axis. */
	static double radius(Model3d.Part p) {
		double r = 0;
		for (double[] t : p.tris) {
			for (int v = 0; v < 3; v++) {
				r = Math.max(r, Math.hypot(t[3 * v + 1], t[3 * v + 2]));
			}
		}
		return r;
	}

	@Test
	void partsListedWiderThanTheirTubeAreDrawnInsideIt() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Rocket r = d.doc.getRocket();
		BodyTube tube = first(r, BodyTube.class);
		NoseCone nose = first(r, NoseCone.class);
		MassComponent wide = new MassComponent(0.05, tube.getOuterRadius() * 1.5, 0.1);
		wide.setName("Too wide");
		tube.addChild(wide);
		// A shoulder entered as wide as the airframe, with no wall (OpenRocket's default 0).
		nose.setAftShoulderRadius(tube.getOuterRadius());
		nose.setAftShoulderLength(0.05);
		nose.setAftShoulderThickness(0);
		FlightConfiguration fc = r.getSelectedConfiguration();
		List<Model3d.Part> parts = Model3d.build(fc, 24);
		assertTrue(radius(part(parts, "Too wide")) <= tube.getInnerRadius() + 1e-6, "inside the bore: " + radius(part(parts, "Too wide")));
		double noseX1 = nose.getLength();
		double shoulder = 0;
		boolean innerWall = false;
		for (double[] t : part(parts, nose.getName()).tris) {
			for (int v = 0; v < 3; v++) {
				if (t[3 * v] > noseX1 + 1e-6) {
					double rr = Math.hypot(t[3 * v + 1], t[3 * v + 2]);
					shoulder = Math.max(shoulder, rr);
					innerWall |= rr > 1e-4 && rr < tube.getInnerRadius() - 5e-4;
				}
			}
		}
		assertTrue(shoulder > 0 && shoulder < tube.getInnerRadius(), "the shoulder fits the tube: " + shoulder);
		assertTrue(innerWall, "a shoulder with no wall entered is drawn with one (its two surfaces would fight)");
		assertTrue(part(parts, tube.getName()).depth == 0 && part(parts, "Too wide").depth == 1);
	}

	@Test
	void aPartHoldingOthersIsOpenedByTheCutAway() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Rocket r = d.doc.getRocket();
		BodyTube tube = first(r, BodyTube.class);
		MassComponent sled = new MassComponent(0.12, tube.getInnerRadius() * 0.9, 0.2);
		sled.setName("Sled");
		MassComponent altimeter = new MassComponent(0.04, tube.getInnerRadius() * 0.3, 0.02);
		altimeter.setName("Altimeter");
		altimeter.setAxialOffset(0.04);
		tube.addChild(sled);
		tube.addChild(altimeter);
		List<Model3d.Part> parts = Model3d.build(r.getSelectedConfiguration(), 24);
		assertTrue(part(parts, "Sled").section, "the sled is cut open to show the altimeter");
		assertFalse(part(parts, "Altimeter").section);
		assertTrue(part(parts, tube.getName()).section);
		assertTrue(parts.stream().filter(p -> p.kind.equals("motor")).allMatch(p -> p.depth >= 1));
	}

	@Test
	void tubeFinsLugsAndRailButtonsAreDrawn() throws Exception {
		Designs.Design d = new Designs().openExample("Tube fin rocket");
		Rocket r = d.doc.getRocket();
		List<Model3d.Part> parts = Model3d.build(r.getSelectedConfiguration(), 24);
		Model3d.Part tubes = parts.stream().filter(p -> p.component instanceof info.openrocket.core.rocketcomponent.TubeFinSet).findFirst()
				.orElseThrow(() -> new AssertionError("tube fins drawn"));
		var tf = (info.openrocket.core.rocketcomponent.TubeFinSet) tubes.component;
		assertEquals(tf.getFinCount(), tubes.fins.size(), "one tube per fin, each with its direction");
		assertTrue(tubes.rMax > tf.getBodyRadius() + tf.getOuterRadius(), "standing out from the body");
		assertTrue(parts.stream().anyMatch(p -> p.component instanceof info.openrocket.core.rocketcomponent.LaunchLug), "launch lug drawn");

		Designs.Design d2 = new Designs().openExample("Dual parachute");
		BodyTube tube = first(d2.doc.getRocket(), BodyTube.class);
		RailButton button = new RailButton();
		button.setName("Button");
		tube.addChild(button);
		List<Model3d.Part> parts2 = Model3d.build(d2.doc.getRocket().getSelectedConfiguration(), 24);
		Model3d.Part b = part(parts2, "Button");
		assertTrue(b.rMax > tube.getOuterRadius() + 0.5 * button.getTotalHeight(), "it stands out of the skin: " + b.rMax);
		assertFalse(b.internal);
	}

	@Test
	void partsInsideAreDrawnAsWhatTheyAreAndStayInTheirTube() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Rocket r = d.doc.getRocket();
		BodyTube tube = first(r, BodyTube.class);
		double bore = tube.getInnerRadius();
		java.util.Map<String, MassComponent.MassComponentType> kinds = new java.util.LinkedHashMap<>();
		kinds.put("Altimeter", MassComponent.MassComponentType.ALTIMETER);
		kinds.put("GPS tracker", MassComponent.MassComponentType.TRACKER);
		kinds.put("9 V battery", MassComponent.MassComponentType.BATTERY);
		kinds.put("1S LiPo", MassComponent.MassComponentType.BATTERY);
		kinds.put("Main charge", MassComponent.MassComponentType.DEPLOYMENTCHARGE);
		kinds.put("U-bolt", MassComponent.MassComponentType.RECOVERYHARDWARE);
		kinds.put("Sled", MassComponent.MassComponentType.MASSCOMPONENT);
		kinds.put("Screw switch", MassComponent.MassComponentType.MASSCOMPONENT);
		kinds.put("Payload", MassComponent.MassComponentType.PAYLOAD);
		for (var e : kinds.entrySet()) {
			// listed wider than the tube on purpose: the shape must still fit inside it
			MassComponent m = new MassComponent(0.08, bore * 1.4, 0.05);
			m.setName(e.getKey());
			m.setMassComponentType(e.getValue());
			tube.addChild(m);
		}
		info.openrocket.core.rocketcomponent.ShockCord sc = new info.openrocket.core.rocketcomponent.ShockCord();
		sc.setName("Shock cord");
		sc.setLength(0.12);
		sc.setRadius(bore * 0.8);
		tube.addChild(sc);
		List<Model3d.Part> parts = Model3d.build(r.getSelectedConfiguration(), 24);
		for (Model3d.Part p : parts) {
			if (p.component instanceof MassComponent || p.component instanceof info.openrocket.core.rocketcomponent.RecoveryDevice
					|| p.component instanceof info.openrocket.core.rocketcomponent.ShockCord) {
				assertFalse(p.tris.isEmpty(), p.name + " is drawn");
				double limit = p.component.getParent() instanceof BodyTube bt ? bt.getInnerRadius() + 1e-6 : 1;
				assertTrue(radius(p) <= limit, p.name + " inside its tube: " + radius(p));
			}
		}
		// not cylinders any more: a board is flat, a coil has many more faces than a slug
		Model3d.Part board = part(parts, "Altimeter");
		double ySpan = 0, zSpan = 0, y0 = 1, y1 = -1, z0 = 1, z1 = -1;
		for (double[] t : board.tris) {
			for (int v = 0; v < 3; v++) {
				y0 = Math.min(y0, t[3 * v + 1]);
				y1 = Math.max(y1, t[3 * v + 1]);
				z0 = Math.min(z0, t[3 * v + 2]);
				z1 = Math.max(z1, t[3 * v + 2]);
			}
		}
		ySpan = y1 - y0;
		zSpan = z1 - z0;
		assertTrue(ySpan < 0.7 * zSpan, "a board, flatter than it is wide: " + ySpan + " vs " + zSpan);
		Model3d.Part cord = parts.stream().filter(p -> p.component instanceof info.openrocket.core.rocketcomponent.ShockCord).findFirst()
				.orElseThrow(() -> new AssertionError("the shock cord is drawn"));
		assertTrue(cord.tris.size() > 500, "a coiled cord: " + cord.tris.size());
	}

	@Test
	void parachutesOpenWhenExplodedAndHardwareHasItsOwnShape() throws Exception {
		Designs.Design d = new Designs().openExample("Dual parachute");
		Rocket r = d.doc.getRocket();
		BodyTube tube = first(r, BodyTube.class);
		String[] names = { "Eyebolt 5/16\"", "Forged eye nut 1/4\"", "Swivel 1000 lb", "Quick link 1/4\"", "Bow shackle 1/4\"", "U-bolt 1/4\"" };
		for (String n : names) {
			MassComponent m = new MassComponent(0.06, 0.015, 0.03);
			m.setName(n);
			m.setMassComponentType(MassComponent.MassComponentType.RECOVERYHARDWARE);
			tube.addChild(m);
		}
		FlightConfiguration fc = r.getSelectedConfiguration();
		List<Model3d.Part> parts = Model3d.build(fc, 32);
		java.util.Set<Integer> sizes = new java.util.HashSet<>();
		for (String n : names) {
			Model3d.Part p = part(parts, n);
			assertFalse(p.tris.isEmpty(), n);
			assertTrue(p.x1 - p.x0 <= 0.06 + 1e-6, n + " stays within its length");
			sizes.add(p.tris.size());
		}
		assertEquals(names.length, sizes.size(), "each fitting is drawn its own way: " + sizes);

		Model3d.Part chute = parts.stream().filter(p -> p.component instanceof info.openrocket.core.rocketcomponent.Parachute).findFirst()
				.orElseThrow();
		assertFalse(chute.open.isEmpty(), "a parachute has its open shape");
		double packedR = chute.rMax, packedLen = chute.x1 - chute.x0;
		chute.opened();
		assertTrue(chute.x1 - chute.x0 > packedLen && chute.rMax > packedR, "open, it is longer (its lines) and wider than packed");
		Model3d.Part streamer = parts.stream().filter(p -> p.component instanceof info.openrocket.core.rocketcomponent.Streamer).findFirst().orElse(null);
		if (streamer != null) {
			assertTrue(streamer.open.isEmpty(), "a streamer stays rolled");
		}
	}
}
