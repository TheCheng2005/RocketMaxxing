package io.github.openrocketmcp.report;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.openrocketmcp.mcp.CallContext;

/**
 * Writes a {@link FlightAnimation} as a looping GIF (plays everywhere: chat, slides, Discord, GitHub) and as an H.264
 * MP4 for presentations, video players and editors: through ffmpeg when it is installed (smaller files), else with
 * JCodec in Java, so every computer gets one. Frames render in parallel batches and stream to the encoders, so memory
 * stays flat however long the animation is.
 */
public final class Video {
	private Video() {
	}

	public record Written(Path gif, Path mp4, int frames, double seconds, long gifBytes, long mp4Bytes, String mp4Note) {
	}

	/**
	 * The ffmpeg executable: $OPENROCKET_MCP_FFMPEG or $FFMPEG if set, else ffmpeg on the PATH or where package
	 * managers put it (an app started from the Dock does not get the terminal's PATH); null when there is none.
	 */
	public static String ffmpeg() {
		if ("none".equals(System.getProperty("openrocketmcp.ffmpeg"))) {
			return null; // tests of the Java encoder on a computer that has ffmpeg
		}
		for (String env : new String[] { "OPENROCKET_MCP_FFMPEG", "FFMPEG" }) {
			String v = System.getenv(env);
			if (v != null && !v.isBlank() && Files.isExecutable(Path.of(v))) {
				return v;
			}
		}
		Path found = io.github.openrocketmcp.Programs.find("ffmpeg");
		return found == null ? null : found.toString();
	}

	/**
	 * @param gif      where to write the GIF, or null for none
	 * @param gifWidth the GIF's width (frames are scaled down to it; GIFs get large fast)
	 * @param mp4      where to write the MP4 at the animation's full size, or null for none
	 */
	public static Written write(FlightAnimation a, Path gif, int gifWidth, Path mp4) throws IOException, InterruptedException {
		if (gif == null && mp4 == null) {
			throw new IllegalArgumentException("Nothing to write: neither a GIF nor an MP4.");
		}
		CallContext ctx = CallContext.current();
		int n = a.frames;
		// Palette from frames spread over the flight, including the key moments.
		List<BufferedImage> samples = new ArrayList<>();
		if (gif != null) {
			for (int i = 0; i < 8; i++) {
				samples.add(scale(a.frame((int) ((long) i * (n - 1) / 7)), gifWidth));
			}
			for (double[] km : a.keyMoments()) {
				samples.add(scale(a.render(km[0], 0), gifWidth));
			}
		}
		Process ff = null;
		OutputStream ffIn = null;
		Mp4Encoder java = null;
		String note = null;
		String exe = mp4 == null ? null : ffmpeg();
		Path ffLog = null;
		boolean done = false;
		long mp4Bytes = 0;
		try {
			if (exe != null) {
				ffLog = Files.createTempFile("ffmpeg", ".log");
				ProcessBuilder pb = new ProcessBuilder(exe, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s",
						a.w + "x" + a.h, "-r", Double.toString(a.fps()), "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf",
						"20", "-preset", "medium", "-movflags", "+faststart", mp4.toAbsolutePath().toString());
				pb.redirectErrorStream(true).redirectOutput(ffLog.toFile());
				ff = pb.start();
				ffIn = ff.getOutputStream();
			} else if (mp4 != null) {
				java = new Mp4Encoder(mp4, a.fps());
			}
			ctx.expect(n, "frames");
			int batch = a.parallelism();
			byte[] rgb = new byte[a.w * a.h * 3];
			try (Gif g = gif == null ? null : new Gif(gif, a.fps(), samples)) {
				for (int from = 0; from < n; from += batch) {
					ctx.checkCancelled();
					for (BufferedImage img : a.frames(from, Math.min(n, from + batch))) {
						if (g != null) {
							g.add(scale(img, gifWidth));
						}
						if (java != null) {
							java.add(img);
						}
						if (ffIn != null) {
							int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
							for (int i = 0; i < px.length; i++) {
								rgb[3 * i] = (byte) (px[i] >> 16);
								rgb[3 * i + 1] = (byte) (px[i] >> 8);
								rgb[3 * i + 2] = (byte) px[i];
							}
							try {
								ffIn.write(rgb);
							} catch (IOException e) {
								ffIn = null; // ffmpeg quit (e.g. no libx264); reported below
							}
						}
						ctx.advance();
					}
				}
			}
			if (java != null) {
				java.finish();
				java.close();
				mp4Bytes = Files.size(mp4);
			}
			if (ff != null) {
				try {
					if (ffIn != null) {
						ffIn.close();
					}
				} catch (IOException ignored) {
					// already gone
				}
				boolean exited = ff.waitFor(120, TimeUnit.SECONDS);
				if (exited && ff.exitValue() == 0 && Files.isRegularFile(mp4)) {
					mp4Bytes = Files.size(mp4);
				} else {
					String log = Files.readString(ffLog, StandardCharsets.UTF_8).strip();
					note = (exited ? "ffmpeg failed" : "ffmpeg did not finish") + (log.isEmpty() ? "" : ": "
							+ log.lines().reduce((x, y) -> y).orElse("")) + (gif != null ? "; the GIF is written." : ".");
					if (!exited) {
						ff.destroyForcibly();
						ff.waitFor(5, TimeUnit.SECONDS);
					}
					Files.deleteIfExists(mp4); // no broken file left where the user would find it
				}
			}
			done = true;
		} finally {
			if (java != null) {
				java.close();
			}
			if (ff != null && ff.isAlive()) {
				ff.destroyForcibly();
			}
			if (ffLog != null) {
				Files.deleteIfExists(ffLog);
			}
			if (!done) { // cancelled or failed: no half-written files
				if (gif != null) {
					Files.deleteIfExists(gif);
				}
				if (mp4 != null) {
					Files.deleteIfExists(mp4);
				}
			}
		}
		return new Written(gif, mp4Bytes > 0 ? mp4 : null, n, a.seconds(), gif == null ? 0 : Files.size(gif), mp4Bytes, note);
	}

	static BufferedImage scale(BufferedImage img, int width) {
		if (width >= img.getWidth()) {
			return img;
		}
		int height = (int) Math.round(img.getHeight() * (double) width / img.getWidth());
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(img, 0, 0, width, height, null);
		g.dispose();
		return out;
	}
}
