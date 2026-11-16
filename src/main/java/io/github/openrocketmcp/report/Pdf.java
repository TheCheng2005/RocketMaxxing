package io.github.openrocketmcp.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A small vector PDF writer for printed templates: pages of lines, curves and Helvetica text, drawn in millimetres
 * from the bottom-left corner so a 50 mm line prints 50 mm long at 100% scale. Nothing is rasterised.
 */
public final class Pdf {
	/** Paper sizes in millimetres. */
	public record Paper(String name, double w, double h) {
		public static final Paper A4 = new Paper("A4", 210, 297), LETTER = new Paper("Letter", 215.9, 279.4);

		public static Paper of(String name) {
			if (name == null || name.isBlank()) {
				return A4;
			}
			return switch (name.trim().toLowerCase(Locale.ROOT)) {
				case "letter", "us letter", "us" -> LETTER;
				case "a4" -> A4;
				case "a3" -> new Paper("A3", 297, 420);
				case "tabloid", "ledger", "11x17" -> new Paper("Tabloid", 279.4, 431.8);
				default -> throw new IllegalArgumentException("Paper \"" + name + "\" is not known: use A4, A3, Letter or Tabloid.");
			};
		}
	}

	private static final double PT = 72 / 25.4;
	private final Paper paper;
	private final List<StringBuilder> pages = new ArrayList<>();
	private StringBuilder c;

	public Pdf(Paper paper) {
		this.paper = paper;
	}

	public Paper paper() {
		return paper;
	}

	public int pages() {
		return pages.size();
	}

	public Pdf page() {
		c = new StringBuilder();
		pages.add(c);
		return this;
	}

	/** Draws on an earlier page again (0 = the first). */
	public Pdf pageAt(int i) {
		c = pages.get(i);
		return this;
	}

	private static String n(double mm) {
		String s = String.format(Locale.ROOT, "%.3f", mm * PT);
		s = s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
		return s.equals("-0") ? "0" : s;
	}

	private Pdf op(String s) {
		c.append(s).append('\n');
		return this;
	}

	public Pdf width(double mm) {
		return op(n(mm) + " w");
	}

	/** Grey level 0 (black) to 1 (white) for lines and text. */
	public Pdf grey(double g) {
		return op(String.format(Locale.ROOT, "%.2f G %.2f g", g, g));
	}

	public Pdf colour(double r, double gr, double b) {
		return op(String.format(Locale.ROOT, "%.3f %.3f %.3f RG %.3f %.3f %.3f rg", r, gr, b, r, gr, b));
	}

	public Pdf dash(double on, double off) {
		return op(on <= 0 ? "[] 0 d" : "[" + n(on) + " " + n(off) + "] 0 d");
	}

	public Pdf solid() {
		return dash(0, 0);
	}

	public Pdf move(double x, double y) {
		return op(n(x) + " " + n(y) + " m");
	}

	public Pdf line(double x, double y) {
		return op(n(x) + " " + n(y) + " l");
	}

	public Pdf curve(double x1, double y1, double x2, double y2, double x3, double y3) {
		return op(n(x1) + " " + n(y1) + " " + n(x2) + " " + n(y2) + " " + n(x3) + " " + n(y3) + " c");
	}

	public Pdf close() {
		return op("h");
	}

	public Pdf stroke() {
		return op("S");
	}

	public Pdf fill() {
		return op("f");
	}

	public Pdf segment(double x0, double y0, double x1, double y1) {
		return move(x0, y0).line(x1, y1).stroke();
	}

	public Pdf rect(double x, double y, double w, double h) {
		return op(n(x) + " " + n(y) + " " + n(w) + " " + n(h) + " re S");
	}

	public Pdf fillRect(double x, double y, double w, double h) {
		return op(n(x) + " " + n(y) + " " + n(w) + " " + n(h) + " re f");
	}

	/** A closed outline through the points (x, y pairs). */
	public Pdf polygon(List<double[]> pts) {
		if (pts.isEmpty()) {
			return this;
		}
		move(pts.get(0)[0], pts.get(0)[1]);
		for (int i = 1; i < pts.size(); i++) {
			line(pts.get(i)[0], pts.get(i)[1]);
		}
		return close().stroke();
	}

	public Pdf polyline(List<double[]> pts) {
		if (pts.size() < 2) {
			return this;
		}
		move(pts.get(0)[0], pts.get(0)[1]);
		for (int i = 1; i < pts.size(); i++) {
			line(pts.get(i)[0], pts.get(i)[1]);
		}
		return stroke();
	}

	/** A circle from four Bézier quarters. */
	public Pdf circle(double cx, double cy, double r) {
		double k = 0.5523 * r;
		move(cx + r, cy);
		curve(cx + r, cy + k, cx + k, cy + r, cx, cy + r);
		curve(cx - k, cy + r, cx - r, cy + k, cx - r, cy);
		curve(cx - r, cy - k, cx - k, cy - r, cx, cy - r);
		curve(cx + k, cy - r, cx + r, cy - k, cx + r, cy);
		return close().stroke();
	}

	public Pdf save() {
		return op("q");
	}

	public Pdf restore() {
		return op("Q");
	}

	/** Moves the origin; between {@link #save} and {@link #restore}. */
	public Pdf translate(double x, double y) {
		return op("1 0 0 1 " + n(x) + " " + n(y) + " cm");
	}

	/** Moves the origin to (x, y) and turns the drawing a quarter anticlockwise. */
	public Pdf turn(double x, double y) {
		return op("0 1 -1 0 " + n(x) + " " + n(y) + " cm");
	}

	/** Draws only inside this rectangle until {@link #restore}. */
	public Pdf clip(double x, double y, double w, double h) {
		return op(n(x) + " " + n(y) + " " + n(w) + " " + n(h) + " re W n");
	}

	/** Text with its baseline starting at (x, y); size in points. Bold uses Helvetica-Bold. */
	public Pdf text(double x, double y, double size, String s, boolean bold) {
		return op("BT /" + (bold ? "F2" : "F1") + " " + size + " Tf " + n(x) + " " + n(y) + " Td " + literal(s) + " Tj ET");
	}

	public Pdf text(double x, double y, double size, String s) {
		return text(x, y, size, s, false);
	}

	/** Text centred on x. */
	public Pdf centred(double x, double y, double size, String s, boolean bold) {
		return text(x - textWidth(s, size, bold) / 2, y, size, s, bold);
	}

	/** Rough Helvetica width in millimetres (good enough for centring and fitting labels). */
	public static double textWidth(String s, double size, boolean bold) {
		double em = 0;
		for (char ch : s.toCharArray()) {
			em += "iljtfI.,:;'| ".indexOf(ch) >= 0 ? 0.28 : "mwMW".indexOf(ch) >= 0 ? 0.85 : Character.isUpperCase(ch) || Character.isDigit(ch) ? 0.64 : 0.53;
		}
		return em * size * (bold ? 1.05 : 1) / PT;
	}

	/** A PDF string in WinAnsi: Latin-1 as octal escapes, "−" as "-", anything else as "?". */
	private static String literal(String s) {
		StringBuilder b = new StringBuilder("(");
		for (char ch : s.toCharArray()) {
			if (ch == '−' || ch == '–') {
				ch = '-';
			}
			if (ch == '(' || ch == ')' || ch == '\\') {
				b.append('\\').append(ch);
			} else if (ch >= 32 && ch < 127) {
				b.append(ch);
			} else if (ch == '×') {
				b.append("\\327");
			} else if (ch >= 160 && ch <= 255) {
				b.append('\\').append(Integer.toOctalString(ch));
			} else {
				b.append('?');
			}
		}
		return b.append(')').toString();
	}

	public byte[] bytes(String title) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		List<Integer> offsets = new ArrayList<>();
		int fixed = 5; // catalog, pages, two fonts, info
		int objs = fixed + pages.size() * 2;
		StringBuilder kids = new StringBuilder();
		for (int i = 0; i < pages.size(); i++) {
			kids.append(fixed + 1 + i * 2).append(" 0 R ");
		}
		write(out, "%PDF-1.4\n%âãÏÓ\n");
		obj(out, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
		obj(out, offsets, 2, "<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pages.size() + " >>");
		obj(out, offsets, 3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
		obj(out, offsets, 4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
		obj(out, offsets, 5, "<< /Title " + literal(title) + " /Producer (RocketMaxxing) >>");
		for (int i = 0; i < pages.size(); i++) {
			int pg = fixed + 1 + i * 2;
			obj(out, offsets, pg, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + n(paper.w()) + " " + n(paper.h())
					+ "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + (pg + 1) + " 0 R >>");
			byte[] content = pages.get(i).toString().getBytes(StandardCharsets.ISO_8859_1);
			offsets.add(out.size());
			write(out, (pg + 1) + " 0 obj\n<< /Length " + content.length + " >>\nstream\n");
			out.writeBytes(content);
			write(out, "\nendstream\nendobj\n");
		}
		int xref = out.size();
		StringBuilder x = new StringBuilder("xref\n0 " + (objs + 1) + "\n0000000000 65535 f \n");
		for (int off : offsets) {
			x.append(String.format(Locale.ROOT, "%010d 00000 n \n", off));
		}
		x.append("trailer\n<< /Size ").append(objs + 1).append(" /Root 1 0 R /Info 5 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
		write(out, x.toString());
		return out.toByteArray();
	}

	public void write(Path file, String title) throws IOException {
		if (file.getParent() != null) {
			Files.createDirectories(file.getParent());
		}
		Files.write(file, bytes(title));
	}

	private static void obj(ByteArrayOutputStream out, List<Integer> offsets, int id, String body) {
		offsets.add(out.size());
		write(out, id + " 0 obj\n" + body + "\nendobj\n");
	}

	private static void write(ByteArrayOutputStream out, String s) {
		out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
	}
}
