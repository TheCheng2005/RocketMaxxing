package io.github.openrocketmcp.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import info.openrocket.core.rocketcomponent.Bulkhead;
import info.openrocket.core.rocketcomponent.CenteringRing;
import info.openrocket.core.rocketcomponent.FinSet;
import info.openrocket.core.rocketcomponent.InnerTube;
import info.openrocket.core.rocketcomponent.LaunchLug;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.RadiusRingComponent;
import info.openrocket.core.rocketcomponent.RailButton;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.rocketcomponent.position.AnglePositionable;
import info.openrocket.core.util.Coordinate;

/**
 * Templates to print and cut at full size, as OpenRocket prints them: fin outlines (with their tabs), centering rings
 * and bulkheads (with the motor tube holes), paper shrouds for transitions, nose cone profiles to check a turned or
 * printed nose against, and a marking guide that wraps round each body tube to mark the fin and rail button lines.
 * Pages carry a 100 mm and a 4 in bar to check the printer kept the scale; parts bigger than a page are tiled.
 */
public final class Templates {
	private Templates() {
	}

	public static final Set<String> KINDS = Set.of("fins", "rings", "transitions", "nose", "marking");
	private static final double MARGIN = 10, HEAD = 14, FOOT = 20, GAP = 6, LINE = 4.4;

	/** One template: its box in millimetres and how to draw it from the box's bottom-left corner. */
	public record Item(String part, String kind, List<String> label, double w, double h, Consumer<Pdf> draw, String same) {
		/** The same template for several parts: cut one for each. */
		Item times(List<String> parts) {
			List<String> l = new ArrayList<>(label);
			String names = String.join(", ", new java.util.LinkedHashSet<>(parts));
			l.set(0, names + ": cut " + parts.size() + label.get(0).substring(label.get(0).indexOf(" (")));
			return new Item(names, kind, l, w, h, draw, same);
		}
	}

	/** What was put on the pages. */
	public record Placed(String part, String kind, String size, int page, int tiles) {
	}

	public record Result(Pdf pdf, List<Placed> placed, List<String> skipped) {
	}

	static String mm(double m) {
		return String.format(Locale.ROOT, "%.1f mm (%.2f in)", m * 1000, m / 0.0254);
	}

	/** The templates for the parts of {@code rocket} ({@code only}: these parts and what is inside them; null = all). */
	public static List<Item> items(Rocket rocket, Set<String> kinds, Set<RocketComponent> only, List<String> skipped) {
		List<Item> out = new ArrayList<>();
		for (RocketComponent c : rocket) {
			if (only != null && !inside(c, only)) {
				continue;
			}
			try {
				if (c instanceof FinSet f && kinds.contains("fins")) {
					out.add(fin(f));
				} else if (c instanceof RadiusRingComponent r && (r instanceof CenteringRing || r instanceof Bulkhead) && kinds.contains("rings")) {
					out.add(ring(r));
				} else if (c instanceof NoseCone n && kinds.contains("nose")) {
					out.add(nose(n));
				} else if (c instanceof Transition t && !(c instanceof NoseCone) && kinds.contains("transitions")) {
					out.add(shroud(t));
				}
				if (c instanceof SymmetricComponent body && !(c instanceof Transition) && kinds.contains("marking")) {
					Item m = marking(body);
					if (m != null) {
						out.add(m);
					}
				}
			} catch (RuntimeException e) {
				skipped.add(c.getName() + ": " + e.getMessage());
			}
		}
		// identical rings and bulkheads print once, with how many to cut
		Map<String, List<Item>> same = new LinkedHashMap<>();
		List<Item> merged = new ArrayList<>();
		for (Item it : out) {
			if (it.same() == null) {
				merged.add(it);
			} else {
				List<Item> group = same.computeIfAbsent(it.same(), k -> new ArrayList<>());
				if (group.isEmpty()) {
					merged.add(it);
				}
				group.add(it);
			}
		}
		merged.replaceAll(it -> it.same() == null || same.get(it.same()).size() < 2 ? it : it.times(same.get(it.same()).stream().map(Item::part).toList()));
		return merged;
	}

	private static boolean inside(RocketComponent c, Set<RocketComponent> only) {
		for (RocketComponent p = c; p != null; p = p.getParent()) {
			if (only.contains(p)) {
				return true;
			}
		}
		return false;
	}

	// ---- the templates ----

	static Item fin(FinSet f) {
		Coordinate[] pts = f.generateContinuousFinAndTabShape();
		if (pts == null || pts.length < 3) {
			throw new IllegalStateException("no outline");
		}
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		for (Coordinate p : pts) {
			minX = Math.min(minX, p.x);
			maxX = Math.max(maxX, p.x);
			minY = Math.min(minY, p.y);
			maxY = Math.max(maxY, p.y);
		}
		List<double[]> outline = new ArrayList<>();
		for (Coordinate p : pts) {
			outline.add(new double[] { (p.x - minX) * 1000, (p.y - minY) * 1000 });
		}
		Coordinate[] root = f.getRootPoints();
		List<double[]> rootLine = new ArrayList<>();
		if (f.hasTab() && root != null) {
			for (Coordinate p : root) {
				rootLine.add(new double[] { (p.x - minX) * 1000, (p.y - minY) * 1000 });
			}
		}
		double w = (maxX - minX) * 1000, h = (maxY - minY) * 1000;
		List<String> label = List.of(f.getName() + ": cut " + f.getFinCount() + (f.getFinCount() == 1 ? " fin" : " fins") + " (front edge at the left)",
				"Thickness " + mm(f.getThickness()) + (f.getMaterial() == null ? "" : ", " + f.getMaterial().getName())
						+ (f.hasTab() ? "; dashed: body line, tab below" : ""));
		return item(f.getName(), "fins", label, w, h, p -> {
			p.width(0.3).grey(0).polygon(outline);
			if (rootLine.size() > 1) {
				p.width(0.2).dash(2, 1.5).polyline(rootLine).solid();
			}
		});
	}

	static Item ring(RadiusRingComponent r) {
		double R = r.getOuterRadius() * 1000;
		List<double[]> holes = new ArrayList<>(); // x, y, radius in mm from the centre
		if (r instanceof CenteringRing) {
			Coordinate at = r.getComponentLocations()[0];
			double x0 = at.x, x1 = x0 + r.getLength();
			for (RocketComponent s : r.getParent().getChildren()) {
				if (!(s instanceof InnerTube t)) {
					continue;
				}
				for (Coordinate tl : t.getComponentLocations()) {
					if (tl.x < x1 && tl.x + t.getLength() > x0) {
						holes.add(new double[] { (tl.y - at.y) * 1000, (tl.z - at.z) * 1000, t.getOuterRadius() * 1000 });
					}
				}
			}
			if (holes.isEmpty() && r.getInnerRadius() > 0) {
				holes.add(new double[] { 0, 0, r.getInnerRadius() * 1000 });
			}
		}
		StringBuilder sizes = new StringBuilder("Outside Ø " + mm(2 * R / 1000));
		if (!holes.isEmpty()) {
			sizes.append(", hole").append(holes.size() > 1 ? "s " + holes.size() + " × " : " ").append("Ø ").append(mm(2 * holes.get(0)[2] / 1000));
		}
		String kind = r instanceof Bulkhead ? "bulkhead" : "centering ring";
		List<String> label = List.of(r.getName() + " (" + kind + ")", sizes.toString());
		double side = 2 * R;
		StringBuilder key = new StringBuilder(kind + String.format(Locale.ROOT, ":%.1f", 2 * R));
		holes.forEach(hole -> key.append(String.format(Locale.ROOT, ":%.1f,%.1f,%.1f", hole[0], hole[1], hole[2])));
		return new Item(r.getName(), "rings", label, side, side, p -> {
			p.width(0.3).grey(0).circle(R, R, R);
			for (double[] hole : holes) {
				p.circle(R + hole[0], R + hole[1], hole[2]);
			}
			double k = Math.min(4, R / 5);
			p.width(0.15).grey(0.4).segment(R - k, R, R + k, R).segment(R, R - k, R, R + k).grey(0);
		}, key.toString());
	}

	/** The flat pattern of a conical shroud: an annular sector with a glue strip along one edge. */
	static Item shroud(Transition t) {
		double a = t.getForeRadius() * 1000, b = t.getAftRadius() * 1000, L = t.getLength() * 1000;
		if (L <= 0) {
			throw new IllegalStateException("no length");
		}
		double r1 = Math.min(a, b), r2 = Math.max(a, b);
		String note = t.getShapeType() == Transition.Shape.CONICAL ? "" : " (a cone between its ends; the part is " + t.getShapeType().name().toLowerCase(Locale.ROOT) + ")";
		List<String> label = List.of(t.getName() + ": paper shroud" + note, "Ends Ø " + mm(2 * r1 / 1000) + " and Ø " + mm(2 * r2 / 1000)
				+ ", length " + mm(L / 1000) + "; glue on the dashed strip");
		if (r2 - r1 < 0.05) { // a straight tube: a rectangle
			double w = 2 * Math.PI * r2, glue = 10;
			return item(t.getName(), "transitions", label, w + glue, L, p -> {
				p.width(0.3).grey(0).rect(0, 0, w, L);
				p.width(0.2).dash(2, 1.5).rect(w, 0, glue, L).solid();
			});
		}
		double s = Math.hypot(L, r2 - r1), R2 = r2 * s / (r2 - r1), R1 = R2 - s, th = 2 * Math.PI * r2 / R2, glue = Math.min(10 / R2, 0.4);
		List<double[]> outer = new ArrayList<>(), inner = new ArrayList<>(), tab = new ArrayList<>();
		int n = 120;
		for (int i = 0; i <= n; i++) {
			double u = th * i / n;
			outer.add(new double[] { R2 * Math.sin(u), -R2 * Math.cos(u) });
			inner.add(new double[] { R1 * Math.sin(u), -R1 * Math.cos(u) });
		}
		for (int i = 0; i <= 12; i++) {
			double u = th + glue * i / 12;
			tab.add(new double[] { R2 * Math.sin(u), -R2 * Math.cos(u) });
		}
		List<double[]> all = new ArrayList<>(outer);
		all.addAll(inner);
		all.addAll(tab);
		all.add(new double[] { R1 * Math.sin(th + glue), -R1 * Math.cos(th + glue) });
		double minX = all.stream().mapToDouble(q -> q[0]).min().orElse(0), maxX = all.stream().mapToDouble(q -> q[0]).max().orElse(0);
		double minY = all.stream().mapToDouble(q -> q[1]).min().orElse(0), maxY = all.stream().mapToDouble(q -> q[1]).max().orElse(0);
		Consumer<List<double[]>> shift = l -> l.replaceAll(q -> new double[] { q[0] - minX, q[1] - minY });
		shift.accept(outer);
		shift.accept(inner);
		shift.accept(tab);
		double[] tabIn = { R1 * Math.sin(th + glue) - minX, -R1 * Math.cos(th + glue) - minY };
		return item(t.getName(), "transitions", label, maxX - minX, maxY - minY, p -> {
			List<double[]> shape = new ArrayList<>(outer);
			for (int i = inner.size() - 1; i >= 0; i--) {
				shape.add(inner.get(i));
			}
			p.width(0.3).grey(0).polygon(shape);
			List<double[]> strip = new ArrayList<>();
			strip.add(outer.get(outer.size() - 1));
			strip.addAll(tab);
			strip.add(tabIn);
			strip.add(inner.get(inner.size() - 1));
			p.width(0.2).dash(2, 1.5).polyline(strip).solid();
		});
	}

	/** Half the nose cone's profile at full size, with its shoulder: a gauge for a turned or printed nose. */
	static Item nose(NoseCone n) {
		double L = n.getLength();
		if (L <= 0) {
			throw new IllegalStateException("no length");
		}
		List<double[]> prof = new ArrayList<>();
		int steps = 80;
		boolean flipped = n.isFlipped();
		for (int i = 0; i <= steps; i++) {
			double x = L * i / steps;
			prof.add(new double[] { x * 1000, n.getRadius(flipped ? L - x : x) * 1000 });
		}
		double sl = n.getShoulderLength() * 1000, sr = n.getShoulderRadius() * 1000, R = Math.max(n.getForeRadius(), n.getAftRadius()) * 1000;
		double w = L * 1000 + sl, h = R;
		List<String> label = List.of(n.getName() + ": profile (" + n.getShapeType().name().toLowerCase(Locale.ROOT) + "), half, at full size",
				"Length " + mm(L) + ", base Ø " + mm(2 * R / 1000) + (sl > 0 ? "; dashed: shoulder" : "") + "; bottom edge: centre line");
		return item(n.getName(), "nose", label, w, h, p -> {
			List<double[]> shape = new ArrayList<>();
			shape.add(new double[] { 0, 0 });
			shape.addAll(prof);
			shape.add(new double[] { L * 1000, 0 });
			p.width(0.3).grey(0).polygon(shape);
			if (sl > 0 && sr > 0) {
				p.width(0.2).dash(2, 1.5).rect(L * 1000, 0, sl, sr).solid();
			}
			for (int i = 1; i < 10; i++) { // ticks every tenth of the length
				double x = L * 1000 * i / 10;
				p.width(0.15).grey(0.5).segment(x, 0, x, 2).grey(0);
			}
		});
	}

	/** A strip to wrap round a body tube with the fin, lug and rail button lines on it. */
	static Item marking(SymmetricComponent body) {
		List<double[]> lines = new ArrayList<>(); // angle (rad), kind (0 fin, 1 lug/button)
		List<String> names = new ArrayList<>();
		for (RocketComponent c : body.getChildren()) {
			if (c instanceof FinSet f) {
				for (int i = 0; i < f.getFinCount(); i++) {
					lines.add(new double[] { f.getAngleOffset() + f.getBaseRotation() + 2 * Math.PI * i / f.getFinCount(), 0 });
				}
				names.add(f.getName());
			} else if ((c instanceof LaunchLug || c instanceof RailButton) && c instanceof AnglePositionable a) {
				lines.add(new double[] { a.getAngleOffset(), 1 });
				names.add(c.getName());
			}
		}
		if (lines.isEmpty()) {
			return null;
		}
		double r = body.getAftRadius() * 1000, C = 2 * Math.PI * r, H = 40, over = 12;
		List<String> label = List.of(body.getName() + ": marking guide, wrap round the tube (Ø " + mm(2 * r / 1000) + ")",
				"Solid: fins (" + String.join(", ", names) + "); dotted: lug or rail buttons; glue over the dashed end");
		return item(body.getName(), "marking", label, C + over, H, p -> {
			p.width(0.3).grey(0).rect(0, 0, C, H);
			p.width(0.2).dash(2, 1.5).rect(C, 0, over, H).solid();
			for (double[] l : lines) {
				double u = ((l[0] % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
				double x = C * u / (2 * Math.PI);
				if (l[1] == 0) {
					p.width(0.35).segment(x, 0, x, H);
				} else {
					p.width(0.3).dash(0.6, 1).segment(x, 0, x, H).solid();
				}
				p.text(x + 0.8, H - 4, 6, String.format(Locale.ROOT, "%.0f°", Math.toDegrees(u)), false);
			}
		});
	}

	private static Item item(String part, String kind, List<String> label, double w, double h, Consumer<Pdf> draw) {
		return new Item(part, kind, label, w, h, draw, null);
	}

	// ---- pages ----

	/** A template's whole box with its label: width and height, turned a quarter when {@code turned}. */
	private static double[] box(Item it, boolean turned) {
		double w = Math.max(it.w(), labelWidth(it)), h = it.h() + it.label().size() * LINE + 1;
		return turned ? new double[] { h, w } : new double[] { w, h };
	}

	/**
	 * Lays the templates out on pages, tallest first, in rows from the top. A template too big for a page is turned
	 * sideways if that fits, else cut into pieces (turned if that takes fewer) that are laid out like the rest, so the
	 * strips of a long nose profile share sheets.
	 */
	public static Result print(String title, List<Item> items, Pdf.Paper paper) {
		double uw = paper.w() - 2 * MARGIN, uh = paper.h() - 2 * MARGIN - HEAD - FOOT, ux = MARGIN, uy = MARGIN + FOOT;
		record Piece(Item it, boolean turned, double w, double h, Consumer<Pdf> draw, String label, int of) {
		}
		List<Piece> all = new ArrayList<>();
		for (Item it : items) {
			double[] a = box(it, false), t = box(it, true);
			if (a[0] <= uw && a[1] <= uh) {
				all.add(new Piece(it, false, a[0], a[1], p -> draw(p, it, 0, 0, false), null, 1));
				continue;
			}
			if (t[0] <= uw && t[1] <= uh) {
				all.add(new Piece(it, true, t[0], t[1], p -> draw(p, it, 0, 0, true), null, 1));
				continue;
			}
			double pw = uw, ph = uh - LINE - 3; // room for the piece's own line
			int ca = (int) Math.ceil(a[0] / pw), ra = (int) Math.ceil(a[1] / ph), ct = (int) Math.ceil(t[0] / pw), rt = (int) Math.ceil(t[1] / ph);
			boolean turn = ct * rt < ca * ra;
			double[] b = turn ? t : a;
			int cols = turn ? ct : ca, rows = turn ? rt : ra, n = cols * rows;
			for (int r = 0; r < rows; r++) {
				for (int c = 0; c < cols; c++) {
					double x0 = c * pw, w = Math.min(pw, b[0] - x0), top = b[1] - r * ph, h = Math.min(ph, top);
					String label = it.part() + ": piece " + (r * cols + c + 1) + " of " + n + " (row " + (r + 1) + " from the top, column " + (c + 1)
							+ " from the left); cut on the dashed lines and tape the edges together";
					all.add(new Piece(it, turn, w, h + LINE + 1, p -> {
						p.save().clip(0, 0, w, h);
						p.save().translate(-x0, -(top - h));
						draw(p, it, 0, 0, turn);
						p.restore().restore();
						p.width(0.2).grey(0.45).dash(3, 2).rect(0, 0, w, h).solid().grey(0);
						p.grey(0.3).text(0, h + 1.5, 6.5, label, false).grey(0);
					}, label, n));
				}
			}
		}
		// tallest first, in rows across the page
		record Spot(Piece pc, int page, double x, double y) {
		}
		List<Piece> sorted = new ArrayList<>(all);
		sorted.sort(Comparator.comparingDouble((Piece p) -> -p.h()));
		List<Spot> spots = new ArrayList<>();
		int page = -1;
		double cx = 0, cy = 0, shelf = 0;
		for (Piece pc : sorted) {
			if (page < 0 || cx + pc.w() > uw) {
				cy += shelf + (shelf > 0 ? GAP : 0);
				cx = 0;
				shelf = 0;
			}
			if (page < 0 || cy + pc.h() > uh) {
				page++;
				cx = cy = shelf = 0;
			}
			spots.add(new Spot(pc, page, cx, cy));
			cx += pc.w() + GAP;
			shelf = Math.max(shelf, pc.h());
		}
		int pages = page + 1;
		Pdf pdf = new Pdf(paper);
		for (int pg = 0; pg < pages; pg++) {
			pdf.page();
			frame(pdf, title, pg + 1, pages);
		}
		Map<Item, Placed> placed = new LinkedHashMap<>();
		for (Item it : items) {
			placed.put(it, null);
		}
		for (Spot s : spots) {
			pdf.pageAt(s.page()).save().translate(ux + s.x(), uy + uh - s.y() - s.pc().h());
			s.pc().draw().accept(pdf);
			pdf.restore();
			Item it = s.pc().it();
			Placed was = placed.get(it);
			placed.put(it, new Placed(it.part(), it.kind(), size(it), was == null ? s.page() + 1 : Math.min(was.page(), s.page() + 1), s.pc().of()));
		}
		return new Result(pdf, new ArrayList<>(placed.values()), List.of());
	}

	private static String size(Item it) {
		return String.format(Locale.ROOT, "%.0f × %.0f mm", it.w(), it.h());
	}

	private static double labelWidth(Item it) {
		double w = 0;
		for (int i = 0; i < it.label().size(); i++) {
			w = Math.max(w, Pdf.textWidth(it.label().get(i), i == 0 ? 7.5 : 6.5, i == 0));
		}
		return Math.min(w, 120);
	}

	/** Draws a template with its label, its box's bottom-left corner at (x, y); turned a quarter anticlockwise. */
	private static void draw(Pdf pdf, Item it, double x, double y, boolean turned) {
		pdf.save();
		if (turned) {
			pdf.turn(x + box(it, false)[1], y);
		} else {
			pdf.translate(x, y);
		}
		it.draw().accept(pdf);
		double ly = it.h() + 1 + (it.label().size() - 1) * LINE + 0.8;
		for (int i = 0; i < it.label().size(); i++) {
			pdf.grey(i == 0 ? 0 : 0.3).text(0, ly - i * LINE, i == 0 ? 7.5 : 6.5, it.label().get(i), i == 0);
		}
		pdf.grey(0).restore();
	}

	/** Title, page number and the scale bars that say whether it printed at 100%. */
	private static void frame(Pdf pdf, String title, int page, int pages) {
		Pdf.Paper p = pdf.paper();
		pdf.grey(0).text(MARGIN, p.h() - MARGIN - 6, 11, title, true);
		String pn = "Page " + page + " of " + pages;
		pdf.grey(0.4).text(p.w() - MARGIN - Pdf.textWidth(pn, 8, false), p.h() - MARGIN - 6, 8, pn, false).grey(0);
		double in = 25.4, y = MARGIN + 1;
		pdf.width(0.4).segment(MARGIN, y, MARGIN + 4 * in, y);
		for (int i = 0; i <= 8; i++) {
			pdf.segment(MARGIN + i * in / 2, y, MARGIN + i * in / 2, y + (i % 2 == 0 ? 2.5 : 1.5));
		}
		pdf.text(MARGIN + 4 * in + 3, y, 6.5, "4 in", false);
		y += 7;
		pdf.segment(MARGIN, y, MARGIN + 100, y);
		for (int i = 0; i <= 10; i++) {
			pdf.segment(MARGIN + i * 10, y, MARGIN + i * 10, y + (i % 5 == 0 ? 2.5 : 1.5));
		}
		pdf.text(MARGIN + 4 * in + 3, y, 6.5, "100 mm", false);
		pdf.grey(0.35).text(MARGIN, y + 6, 7, "Print at 100% (actual size, not \"fit to page\"); the bars must measure 100 mm and 4 in.", false).grey(0);
	}
}
