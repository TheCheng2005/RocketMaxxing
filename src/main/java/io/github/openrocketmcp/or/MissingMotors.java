package io.github.openrocketmcp.or;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import info.openrocket.core.motor.IgnitionEvent;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;

/**
 * Motors a design file uses that the motor database does not have (a team's own .rse or .eng, imported into
 * OpenRocket on another computer). OpenRocket keeps only a motor's name in the file, and leaves the mount empty when it
 * cannot find it, forgetting where it was. This reads the file for every motor, configuration and mount, so the motor
 * goes back in its place as soon as its file is imported (or straight away, when the team's motors already have it).
 */
public final class MissingMotors {
	private MissingMotors() {
	}

	/** One motor as the file has it: in which configuration and mount, and how it is lit. */
	public record Use(String configId, String configName, String mountId, String mountName, String manufacturer, String designation,
			String digest, String delay, String ignitionEvent, double ignitionDelay) {
		public String label() {
			return designation + " (" + manufacturer + ")";
		}
	}

	/** Every motor the design file names, with where it goes. Empty when the file cannot be read as OpenRocket XML. */
	public static List<Use> read(Path file) {
		List<Use> out = new ArrayList<>();
		try {
			var f = DocumentBuilderFactory.newInstance();
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			f.setExpandEntityReferences(false);
			var doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml(file)));
			java.util.Map<String, String> configNames = new java.util.HashMap<>();
			NodeList cfgs = doc.getElementsByTagName("motorconfiguration");
			for (int i = 0; i < cfgs.getLength(); i++) {
				Element c = (Element) cfgs.item(i);
				configNames.put(c.getAttribute("configid"), text(c, "name"));
			}
			NodeList mounts = doc.getElementsByTagName("motormount");
			for (int i = 0; i < mounts.getLength(); i++) {
				Element mm = (Element) mounts.item(i);
				Element comp = (Element) mm.getParentNode();
				String mountId = text(comp, "id"), mountName = text(comp, "name");
				for (Element m : children(mm, "motor")) {
					String cid = m.getAttribute("configid");
					String event = text(mm, "ignitionevent");
					double delay = num(text(mm, "ignitiondelay"));
					for (Element ic : children(mm, "ignitionconfiguration")) {
						if (cid.equals(ic.getAttribute("configid"))) {
							event = text(ic, "ignitionevent");
							delay = num(text(ic, "ignitiondelay"));
						}
					}
					out.add(new Use(cid, configNames.getOrDefault(cid, ""), mountId, mountName, text(m, "manufacturer"),
							text(m, "designation"), text(m, "digest"), text(m, "delay"), event, delay));
				}
			}
		} catch (Exception | LinkageError e) {
			// Not OpenRocket XML we can read: nothing to put back.
		}
		return out;
	}

	/** The uses whose mount the opened design left empty. */
	public static List<Use> missing(Rocket rocket, List<Use> uses) {
		List<Use> out = new ArrayList<>();
		for (Use u : uses) {
			MotorMount mm = mount(rocket, u);
			if (mm == null) {
				continue;
			}
			FlightConfigurationId id = new FlightConfigurationId(u.configId());
			MotorConfiguration mc = mm.getMotorConfig(id);
			if (mc == null || mc.getMotor() == null) {
				// a configuration the file does not name (OpenRocket's default one): the name the design shows for it
				FlightConfiguration fc = rocket.containsFlightConfigurationID(id) ? rocket.getFlightConfiguration(id) : null;
				String name = !u.configName().isEmpty() || fc == null ? u.configName() : fc.getName();
				out.add(new Use(u.configId(), name, u.mountId(), u.mountName(), u.manufacturer(), u.designation(), u.digest(), u.delay(),
						u.ignitionEvent(), u.ignitionDelay()));
			}
		}
		return out;
	}

	/**
	 * Puts each missing motor the database now has back where the file had it, with its delay and ignition. Returns what
	 * was put back; those uses leave the list.
	 */
	public static List<String> restore(Designs.Design d) {
		List<String> done = new ArrayList<>();
		Rocket rocket = d.doc.getRocket();
		for (Use u : new ArrayList<>(d.missingMotors)) {
			ThrustCurveMotor motor = find(u);
			MotorMount mm = mount(rocket, u);
			if (motor == null || mm == null) {
				continue;
			}
			FlightConfigurationId id = new FlightConfigurationId(u.configId());
			if (!rocket.containsFlightConfigurationID(id)) {
				continue;
			}
			MotorConfiguration mc = new MotorConfiguration(mm, id);
			mc.setMotor(motor);
			mc.setEjectionDelay(delay(u.delay(), motor));
			IgnitionEvent ev = event(u.ignitionEvent());
			if (ev != null) {
				mc.setIgnitionEvent(ev);
			}
			if (Double.isFinite(u.ignitionDelay())) {
				mc.setIgnitionDelay(u.ignitionDelay());
			}
			mm.setMotorConfig(mc, id);
			FlightConfiguration fc = rocket.getFlightConfiguration(id);
			if (fc != null) {
				fc.update();
			}
			d.missingMotors.remove(u);
			done.add(u.label() + " in " + ((RocketComponent) mm).getName()
					+ (u.configName().isEmpty() ? "" : ", configuration \"" + u.configName() + "\""));
		}
		if (!done.isEmpty()) {
			rocket.fireComponentChangeEvent(info.openrocket.core.rocketcomponent.ComponentChangeEvent.MOTOR_CHANGE);
		}
		return done;
	}

	/** The motor by its digest (the exact curve), else by maker and name. */
	static ThrustCurveMotor find(Use u) {
		if (!u.digest().isEmpty()) {
			try {
				ThrustCurveMotor m = Motors.find(u.digest(), null);
				if (m != null && u.digest().equalsIgnoreCase(m.getDigest())) {
					return m;
				}
			} catch (RuntimeException e) {
				// not by its curve: by its name below
			}
		}
		try {
			return Motors.find(u.designation(), u.manufacturer());
		} catch (RuntimeException e) {
			return null;
		}
	}

	static MotorMount mount(Rocket rocket, Use u) {
		for (RocketComponent c : rocket) {
			if (c.getID().toString().equals(u.mountId()) && c instanceof MotorMount mm) {
				return mm;
			}
		}
		return null;
	}

	static double delay(String s, Motor m) {
		if (s == null || s.isBlank()) {
			return Motors.defaultDelay(m);
		}
		if (s.equalsIgnoreCase("none") || s.equalsIgnoreCase("plugged")) {
			return Motor.PLUGGED_DELAY;
		}
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return Motors.defaultDelay(m);
		}
	}

	static IgnitionEvent event(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		String k = s.trim().toLowerCase(Locale.ROOT);
		for (IgnitionEvent e : IgnitionEvent.values()) {
			if (e.name().toLowerCase(Locale.ROOT).equals(k) || e.name.equalsIgnoreCase(k)) {
				return e;
			}
		}
		return k.startsWith("ejection") ? IgnitionEvent.EJECTION_CHARGE : null;
	}

	/** The design's XML: an .ork is a zip (or gzip, or plain XML in old files). */
	static byte[] xml(Path file) throws IOException {
		byte[] raw = Files.readAllBytes(file);
		if (raw.length > 1 && raw[0] == 'P' && raw[1] == 'K') {
			try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(raw))) {
				for (ZipEntry e; (e = z.getNextEntry()) != null;) {
					String n = e.getName().toLowerCase(Locale.ROOT);
					if (n.endsWith(".ork") || n.endsWith(".xml") || n.endsWith(".rkt")) {
						return z.readAllBytes();
					}
				}
			}
			return new byte[0];
		}
		if (raw.length > 1 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b) {
			try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
				return in.readAllBytes();
			}
		}
		return raw;
	}

	private static List<Element> children(Element e, String tag) {
		List<Element> out = new ArrayList<>();
		for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element c && c.getTagName().equals(tag)) {
				out.add(c);
			}
		}
		return out;
	}

	private static String text(Element e, String tag) {
		List<Element> c = children(e, tag);
		return c.isEmpty() ? "" : c.get(0).getTextContent().trim();
	}

	private static double num(String s) {
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}
}
