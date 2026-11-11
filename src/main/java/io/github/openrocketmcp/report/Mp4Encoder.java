package io.github.openrocketmcp.report;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.jcodec.codecs.h264.H264Encoder;
import org.jcodec.common.Codec;
import org.jcodec.common.MuxerTrack;
import org.jcodec.common.VideoCodecMeta;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Size;
import org.jcodec.containers.mp4.muxer.MP4Muxer;
import org.jcodec.scale.ColorUtil;
import org.jcodec.scale.Transform;

/**
 * The MP4 encoder in Java, for computers without ffmpeg: H.264 through JCodec, even frame sizes. H.264 is made of
 * groups of frames that each start afresh from a key frame, so the groups are encoded side by side on every core
 * and written in order: the same file as one encoder working through the frames, several times sooner.
 */
final class Mp4Encoder implements AutoCloseable {
	/** Frames per group: JCodec's own key-frame interval. */
	static final int GROUP = 25;
	private static final int TIMESCALE = 1000;

	private record Encoded(ByteBuffer data, boolean key) {
	}

	private final SeekableByteChannel out;
	private final MP4Muxer muxer;
	private final Transform toYuv = ColorUtil.getTransform(
			ColorSpace.RGB, ColorSpace.YUV420J);
	private final ExecutorService pool;
	private final ArrayDeque<Future<List<Encoded>>> encoding = new ArrayDeque<>();
	private final int fpsTimes1000;
	private MuxerTrack track;
	private List<Picture> group = new ArrayList<>(GROUP);
	private int atOnce = 1;
	private long frameNo;
	private boolean open = true;

	Mp4Encoder(Path file, double fps) throws IOException {
		out = NIOUtils.writableChannel(file.toFile());
		muxer = MP4Muxer.createMP4MuxerToChannel(out);
		fpsTimes1000 = (int) Math.round(fps * TIMESCALE);
		pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), r -> {
			Thread t = new Thread(r, "mp4-encoder");
			t.setDaemon(true);
			return t;
		});
	}

	void add(BufferedImage img) throws IOException {
		int w = img.getWidth() & ~1, h = img.getHeight() & ~1;
		if (track == null) {
			track = muxer.addVideoTrack(Codec.H264, VideoCodecMeta.createSimpleVideoCodecMeta(
					new Size(w, h), ColorSpace.YUV420J));
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
	private Picture yuv(BufferedImage img, int w, int h) {
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
		var in = Picture.createPicture(w, h, new byte[][] { rgb }, ColorSpace.RGB);
		var pic = Picture.create(w, h, ColorSpace.YUV420J);
		toYuv.transform(in, pic);
		return pic;
	}

	private void submit() throws IOException {
		while (encoding.size() >= atOnce) {
			writeNext();
		}
		List<Picture> pics = group;
		group = new ArrayList<>(GROUP);
		encoding.add(pool.submit(() -> {
			var enc = H264Encoder.createH264Encoder();
			enc.setKeyInterval(GROUP);
			ByteBuffer work = ByteBuffer.allocate(enc.estimateBufferSize(pics.get(0)));
			List<Encoded> done = new ArrayList<>(pics.size());
			for (var pic : pics) {
				work.clear();
				var f = enc.encodeFrame(pic, work);
				ByteBuffer copy = ByteBuffer.allocate(f.getData().remaining());
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
		} catch (ExecutionException e) {
			throw new IOException("Could not encode the video: " + e.getCause(), e.getCause());
		}
		for (Encoded f : frames) {
			track.addFrame(Packet.createPacket(f.data(), frameNo * TIMESCALE, fpsTimes1000, TIMESCALE, frameNo,
					f.key() ? Packet.FrameType.KEY : Packet.FrameType.INTER, null));
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
