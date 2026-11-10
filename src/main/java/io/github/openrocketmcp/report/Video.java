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
	 * The MP4 encoder in Java, for computers without ffmpeg: H.264 through JCodec, even frame sizes. H.264 is made of
	 * groups of frames that each start afresh from a key frame, so the groups are encoded side by side on every core
	 * and written in order: the same file as one encoder working through the frames, several times sooner.
	 */
	static final class JavaMp4 implements AutoCloseable {
		/** Frames per group: JCodec's own key-frame interval. */
		static final int GROUP = 25;
		private static final int TIMESCALE = 1000;

		private record Encoded(java.nio.ByteBuffer data, boolean key) {
		}

		private final org.jcodec.common.io.SeekableByteChannel out;
		private final org.jcodec.containers.mp4.muxer.MP4Muxer muxer;
		private final org.jcodec.scale.Transform toYuv = org.jcodec.scale.ColorUtil.getTransform(
				org.jcodec.common.model.ColorSpace.RGB, org.jcodec.common.model.ColorSpace.YUV420J);
		private final java.util.concurrent.ExecutorService pool;
		private final java.util.ArrayDeque<java.util.concurrent.Future<List<Encoded>>> encoding = new java.util.ArrayDeque<>();
		private final int fpsTimes1000;
		private org.jcodec.common.MuxerTrack track;
		private List<org.jcodec.common.model.Picture> group = new ArrayList<>(GROUP);
		private int atOnce = 1;
		private long frameNo;
		private boolean open = true;

		JavaMp4(Path file, double fps) throws IOException {
			out = org.jcodec.common.io.NIOUtils.writableChannel(file.toFile());
			muxer = org.jcodec.containers.mp4.muxer.MP4Muxer.createMP4MuxerToChannel(out);
			fpsTimes1000 = (int) Math.round(fps * TIMESCALE);
			pool = java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), r -> {
				Thread t = new Thread(r, "mp4-encoder");
				t.setDaemon(true);
				return t;
			});
		}

		void add(BufferedImage img) throws IOException {
			int w = img.getWidth() & ~1, h = img.getHeight() & ~1;
			if (track == null) {
				track = muxer.addVideoTrack(org.jcodec.common.Codec.H264, org.jcodec.common.VideoCodecMeta.createSimpleVideoCodecMeta(
						new org.jcodec.common.model.Size(w, h), org.jcodec.common.model.ColorSpace.YUV420J));
				// Groups in work at once: one per core, fewer when memory is short (a group holds its frames as YUV).
				Runtime rt = Runtime.getRuntime();
				long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
				atOnce = (int) Math.max(1, Math.min(rt.availableProcessors(), free / 3 / (4L * GROUP * w * h)));
			}
			group.add(yuv(img, w, h));
			if (group.size() == GROUP) {
				submit();
			}
		}

		/** The frame as JCodec's YUV picture (its bytes are the values less 128). */
		private org.jcodec.common.model.Picture yuv(BufferedImage img, int w, int h) {
			byte[] rgb = new byte[3 * w * h];
			int iw = img.getWidth();
			int[] px = img.getRaster().getDataBuffer() instanceof DataBufferInt b && img.getType() == BufferedImage.TYPE_INT_RGB
					? b.getData() : img.getRGB(0, 0, iw, img.getHeight(), null, 0, iw);
			for (int y = 0, o = 0; y < h; y++) {
				for (int x = 0, i = y * iw; x < w; x++, i++) {
					int c = px[i];
					rgb[o++] = (byte) (((c >> 16) & 0xff) - 128);
					rgb[o++] = (byte) (((c >> 8) & 0xff) - 128);
					rgb[o++] = (byte) ((c & 0xff) - 128);
				}
			}
			var in = org.jcodec.common.model.Picture.createPicture(w, h, new byte[][] { rgb }, org.jcodec.common.model.ColorSpace.RGB);
			var pic = org.jcodec.common.model.Picture.create(w, h, org.jcodec.common.model.ColorSpace.YUV420J);
			toYuv.transform(in, pic);
			return pic;
		}

		private void submit() throws IOException {
			while (encoding.size() >= atOnce) {
				writeNext();
			}
			List<org.jcodec.common.model.Picture> pics = group;
			group = new ArrayList<>(GROUP);
			encoding.add(pool.submit(() -> {
				var enc = org.jcodec.codecs.h264.H264Encoder.createH264Encoder();
				enc.setKeyInterval(GROUP);
				java.nio.ByteBuffer work = java.nio.ByteBuffer.allocate(enc.estimateBufferSize(pics.get(0)));
				List<Encoded> done = new ArrayList<>(pics.size());
				for (var pic : pics) {
					work.clear();
					var f = enc.encodeFrame(pic, work);
					java.nio.ByteBuffer copy = java.nio.ByteBuffer.allocate(f.getData().remaining());
					copy.put(f.getData()).flip();
					done.add(new Encoded(copy, f.isKeyFrame()));
				}
				return done;
			}));
		}

		/** Waits for the oldest group in work and writes its frames. */
		private void writeNext() throws IOException {
			List<Encoded> frames;
			try {
				frames = encoding.remove().get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while encoding the video.");
			} catch (java.util.concurrent.ExecutionException e) {
				throw new IOException("Could not encode the video: " + e.getCause(), e.getCause());
			}
			for (Encoded f : frames) {
				track.addFrame(org.jcodec.common.model.Packet.createPacket(f.data(), frameNo * TIMESCALE, fpsTimes1000, TIMESCALE, frameNo,
						f.key() ? org.jcodec.common.model.Packet.FrameType.KEY : org.jcodec.common.model.Packet.FrameType.INTER, null));
				frameNo++;
			}
		}

		/** Encodes what is left and writes the file's index; without it the file does not play. */
		void finish() throws IOException {
			if (!group.isEmpty()) {
				submit();
			}
			while (!encoding.isEmpty()) {
				writeNext();
			}
			muxer.finish();
		}

		@Override
		public void close() throws IOException {
			if (open) {
				open = false;
				pool.shutdownNow();
				out.close();
			}
		}
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
		JavaMp4 java = null;
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
				java = new JavaMp4(mp4, a.fps());
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
