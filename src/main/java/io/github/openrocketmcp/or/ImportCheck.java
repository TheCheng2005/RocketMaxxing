package io.github.openrocketmcp.or;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;

/**
 * What to look at after a design file is opened: what OpenRocket dropped or changed while reading it, what the file
 * says about itself that the design here does not match (length, mass, number of parts), and what a file from another
 * program never carries (motors, simulations). An .ork is OpenRocket's own format and normally reports nothing.
 */
public final class ImportCheck {
	private ImportCheck() {
	}

	/** One finding: "lost" (not brought in), "check" (brought in, may be wrong) or "note" (to do after importing). */
	public record Finding(String level, String text) {
		Map<String, Object> render() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("level", level);
			m.put("text", text);
			return m;
		}
	}

	public record Report(String format, String file, Map<String, Object> cameIn, List<Finding> findings) {
		public Map<String, Object> render() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("format", format);
			m.put("file", file);
			m.put("cameIn", cameIn);
			List<Map<String, Object>> list = new ArrayList<>();
			findings.forEach(f -> list.add(f.render()));
			m.put("findings", list);
			return m;
		}
	}

	/** The format's name from the file's ending. */
	public static String format(Path p) {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
		return n.endsWith(".rkt") ? "RockSim" : n.endsWith(".cdx1") ? "RASAero II" : "OpenRocket";
	}

	public static Report check(Path file, OpenRocketDocument doc, List<String> loaderWarnings) {
		String format = format(file);
		boolean own = format.equals("OpenRocket");
		Rocket r = doc.getRocket();
		List<Finding> out = new ArrayList<>();

		// What OpenRocket said while reading: parts it could not place, values it changed, motors it does not know.
		for (String w : loaderWarnings) {
			String t = plain(w);
			boolean kept = t.toLowerCase(Locale.ROOT).contains("defaulting") || t.toLowerCase(Locale.ROOT).contains("instead");
			add(out, kept ? "check" : "lost", t);
		}

		int parts = 0, stages = 0, mounts = 0, recovery = 0, overridden = 0;
		List<String> empty = new ArrayList<>();
		for (RocketComponent c : r) {
			if (c == r) {
				continue;
			}
			if (c instanceof AxialStage) {
				stages++;
				continue;
			}
			parts++;
			if (c instanceof MotorMount mm && mm.isMotorMount()) {
				mounts++;
			}
			if (c instanceof RecoveryDevice) {
				recovery++;
			}
			if (c.isMassOverridden() || c.isCGOverridden()) {
				overridden++;
			}
			if (c.isAerodynamic() && c.getLength() <= 0 && !(c instanceof info.openrocket.core.rocketcomponent.FinSet)) {
				empty.add(c.getName());
			}
		}
		int withMotors = 0;
		for (FlightConfigurationId id : r.getIds()) {
			if (r.getFlightConfiguration(id).hasMotors()) {
				withMotors++;
			}
		}
		FlightConfiguration fc = r.getSelectedConfiguration();
		Analysis.Stability st = null;
		try {
			st = Analysis.stability(fc, 0.3);
		} catch (RuntimeException e) {
			add(out, "check", "Stability could not be worked out for this design (" + e.getMessage() + "): a part probably came in "
					+ "with a size or place OpenRocket cannot use.");
		}
		Map<String, Object> in = new LinkedHashMap<>();
		in.put("stages", stages);
		in.put("parts", parts);
		in.put("configurationsWithMotors", withMotors);
		in.put("simulations", doc.getSimulationCount());
		if (st != null) {
			in.put("length", Units.fmt(st.length(), Dim.LENGTH));
			in.put("mass", Units.fmt(st.launchMass(), Dim.MASS));
		}

		if (!own) {
			fromFile(file, format, r, parts, st, out);
			if (parts == 0) {
				add(out, "lost", "No parts came in: the file may be empty, or hold a kind of design OpenRocket cannot read.");
			}
			if (mounts == 0 && parts > 0) {
				add(out, "note", "No tube is marked as a motor mount. Tick \"This is a motor mount\" on the motor tube before choosing a motor.");
			} else if (withMotors == 0 && parts > 0) {
				add(out, "note", format.equals("RockSim")
						? "No motors came in: OpenRocket does not read the motors of a RockSim file. Choose them on the Motors page."
						: "No motors came in. Choose them on the Motors page.");
			}
			if (doc.getSimulationCount() == 0 && parts > 0) {
				add(out, "note", "No simulations came in: " + (format.equals("RockSim") ? "RockSim's are not read" : "the file has none that "
						+ "could be read") + ". Add one on the Flights page (launch site, wind and rail are OpenRocket's defaults until you set them).");
			}
			if (recovery == 0 && parts > 0) {
				add(out, "note", "No parachute or streamer came in" + (format.equals("RASAero II") ? ": RASAero keeps recovery with its "
						+ "simulations, not as parts" : "") + ". Add the recovery on the Design page.");
			}
			if (overridden > 0) {
				add(out, "check", overridden + " part" + (overridden == 1 ? " has" : "s have") + " a mass or CG typed in by hand in the "
						+ "file (an override), which is used in place of the mass worked out from size and material. Check they are "
						+ "still right.");
			}
			if (!empty.isEmpty()) {
				add(out, "check", "Came in with no length: " + String.join(", ", empty.subList(0, Math.min(4, empty.size())))
						+ (empty.size() > 4 ? " and " + (empty.size() - 4) + " more" : "") + ". They add nothing to the airframe as they are.");
			}
		}
		if (st != null && (!own || !loaderWarnings.isEmpty())) {
			double m = st.marginCalibers();
			if (Double.isNaN(st.cpX())) {
				if (!own && parts > 0) {
					add(out, "check", "No centre of pressure can be worked out: the design has no fins or body the aerodynamics can use.");
				}
			} else if (st.cpX() < 0 || st.cpX() > st.length() * 1.5 || Math.abs(m) > 15) {
				add(out, "check", "The stability figure is not believable (CP at " + Units.fmt(st.cpX(), Dim.LENGTH) + " on a "
						+ Units.fmt(st.length(), Dim.LENGTH) + " rocket, " + Units.num(m) + " cal): a fin or body part has most likely "
						+ "come in at the wrong size or place. Look at the fins and the parts they sit on.");
			}
			if (!own) {
				List<String> aero = new ArrayList<>();
				for (String w : st.warnings()) {
					aero.add(w.replaceAll("\\s+", " ").trim());
				}
				if (!aero.isEmpty()) {
					add(out, "check", "OpenRocket's aerodynamics are less sure of this shape, so treat CP and drag as rougher than "
							+ "usual: " + String.join("; ", aero) + ".");
				}
			}
		}
		return new Report(format, file.getFileName().toString(), in, out);
	}

	private static void add(List<Finding> out, String level, String text) {
		if (out.stream().noneMatch(f -> f.text().equals(text))) {
			out.add(new Finding(level, text));
		}
	}

	/** OpenRocket's warning without its Java class names, as a sentence. */
	static String plain(String w) {
		String t = w.replaceAll("info\\.openrocket\\.core\\.rocketcomponent\\.([A-Z][A-Za-z]*)", "$1")
				.replaceAll("\\b(?!OpenRocket|RockSim)([A-Z][a-z]+)([A-Z][a-z]+)(?=\\b)", "$1 $2").replaceAll("\\s+", " ").trim();
		t = t.replaceAll("can not be attached to (?=[AEIOU])", "cannot be attached to an ").replace("can not be attached to", "cannot be attached to a")
				.replace(", ignoring component", ": it was left out").replace("Ignoring motor.", "The motor was left out.")
				.replace("Please add it manually.", "Import its file on the Motors page, then choose it.");
		t = t.replace("Ignoring.", "Left out.").replace(", ignoring.", ": left out.");
		return t.endsWith(".") || t.endsWith("!") ? t : t + ".";
	}

	// ------------------------------------------------------------------ what the file says about itself

	/** Compares the design with the numbers the other program wrote in its file. */
	private static void fromFile(Path file, String format, Rocket r, int parts, Analysis.Stability st, List<Finding> out) {
		Document xml;
		try (InputStream in = Files.newInputStream(file)) {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			f.setExpandEntityReferences(false);
			xml = f.newDocumentBuilder().parse(in);
		} catch (Exception e) {
			return; // OpenRocket read it; what cannot be cross-checked is simply not cross-checked
		}
		if (format.equals("RockSim")) {
			rockSim(xml, parts, st, out);
		} else {
			rasAero(xml, r, st, out);
		}
	}

	private static void rockSim(Document xml, int parts, Analysis.Stability st, List<Finding> out) {
		// Every part in a RockSim file has a serial number.
		int inFile = xml.getElementsByTagName("SerialNo").getLength();
		if (inFile > parts) {
			add(out, "lost", "The file has " + inFile + " parts and " + parts + " came in: " + (inFile - parts) + " could not be "
					+ "brought across (see the parts named above, and compare the parts list with RockSim's).");
		}
		double len = number(xml, "MaxLenWithoutFins") / 1000;
		if (st != null && len > 0 && Math.abs(st.length() - len) > Math.max(0.005, 0.03 * len)) {
			add(out, "check", "RockSim gives the rocket's length as " + Units.fmt(len, Dim.LENGTH) + "; here it is "
					+ Units.fmt(st.length(), Dim.LENGTH) + ". A part's length or place, or how the stages stack, differs.");
		}
		double mass = 0;
		NodeList calc = xml.getElementsByTagName("CalcMass");
		for (int i = 0; i < calc.getLength(); i++) {
			Element part = (Element) calc.item(i).getParentNode();
			double known = childNumber(part, "KnownMass");
			mass += "1".equals(childText(part, "UseKnownMass")) && known > 0 ? known : parse(calc.item(i).getTextContent());
		}
		// A file with stage masses typed in uses those in place of the parts'.
		double typed = 0;
		for (String tag : new String[] { "Stage3Mass", "Stage2Mass", "Stage1Mass" }) {
			double v = number(xml, tag);
			typed += Double.isNaN(v) ? 0 : v;
		}
		boolean byHand = typed > 0;
		if (byHand) {
			mass = typed;
		}
		mass /= 1000;
		if (st != null && mass > 0 && Math.abs(st.launchMass() - mass) > Math.max(0.002, 0.1 * mass)) {
			add(out, "check", (byHand ? "The stage masses typed into RockSim add up to " : "RockSim's own part masses add up to ")
					+ Units.fmt(mass, Dim.MASS) + "; here the rocket weighs "
					+ Units.fmt(st.launchMass(), Dim.MASS) + " without motors. The two programs work masses out from materials "
					+ "differently: weigh the real parts, or set the masses that matter by hand.");
		}
	}

	private static void rasAero(Document xml, Rocket r, Analysis.Stability st, List<Finding> out) {
		NodeList designs = xml.getElementsByTagName("RocketDesign");
		if (designs.getLength() == 0) {
			return;
		}
		// The airframe is the parts directly under RocketDesign, each with a place and a length in inches.
		double end = 0;
		for (Node n = designs.item(0).getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e && childText(e, "PartType") != null) {
				double at = childNumber(e, "Location"), len = childNumber(e, "Length");
				if (!Double.isNaN(at) && !Double.isNaN(len)) {
					end = Math.max(end, at + len);
				}
			}
		}
		double len = end * 0.0254;
		if (st != null && len > 0 && Math.abs(st.length() - len) > Math.max(0.005, 0.03 * len)) {
			add(out, "check", "RASAero gives the rocket's length as " + Units.fmt(len, Dim.LENGTH) + "; here it is "
					+ Units.fmt(st.length(), Dim.LENGTH) + ". A part's length or place differs.");
		}
		add(out, "note", "RASAero describes the outside of the rocket only. Masses here come from OpenRocket's default materials"
				+ (number(xml, "SustainerLaunchWt") > 0 ? " and the launch weight and CG typed into RASAero's simulations" : "")
				+ ": check the mass and CG against the real rocket before trusting stability or apogee.");
	}

	private static double number(Document xml, String tag) {
		NodeList l = xml.getElementsByTagName(tag);
		return l.getLength() == 0 ? Double.NaN : parse(l.item(0).getTextContent());
	}

	private static String childText(Element e, String tag) {
		for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element c && c.getTagName().equals(tag)) {
				return c.getTextContent().trim();
			}
		}
		return null;
	}

	private static double childNumber(Element e, String tag) {
		String t = childText(e, tag);
		return t == null ? Double.NaN : parse(t);
	}

	private static double parse(String t) {
		try {
			return Double.parseDouble(t.trim());
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}
}
