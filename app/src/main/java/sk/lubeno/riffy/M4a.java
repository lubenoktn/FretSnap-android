package sk.lubeno.riffy;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/** Prevod WAV na M4A (AAC), aby sa riff dal poslať menší. */
final class M4a {
    private M4a() {
    }

    static void convert(InputStream wav, File out) throws IOException {
        InputStream in = new BufferedInputStream(wav, 1 << 16);
        Wav.Info info = Wav.read(in);
        if (info.bits != 16 && info.bits != 24 && info.bits != 32) throw new IOException("Nepodporovaný formát WAV.");
        int sb = info.bits / 8, fb = info.frameBytes();
        long left = info.frames();

        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, info.rate, info.channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 192000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaMuxer muxer = null;
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo done = new MediaCodec.BufferInfo();
            byte[] frame = new byte[fb];
            boolean inputDone = false, started = false;
            int track = -1;
            long written = 0;
            while (true) {
                if (!inputDone) {
                    int i = codec.dequeueInputBuffer(10000);
                    if (i >= 0) {
                        ByteBuffer b = codec.getInputBuffer(i);
                        b.clear();
                        int frames = (int) Math.min(left, b.remaining() / (2 * info.channels));
                        for (int f = 0; f < frames; f++) {
                            readFully(in, frame);
                            for (int c = 0; c < info.channels; c++) {
                                // Do kodéra ide 16 bitov: horné dva bajty každej vzorky.
                                int at = c * sb + sb - 2;
                                b.put(frame[at]);
                                b.put(frame[at + 1]);
                            }
                        }
                        left -= frames;
                        long time = written * 1_000_000L / info.rate;
                        written += frames;
                        if (frames == 0) {
                            codec.queueInputBuffer(i, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(i, 0, frames * 2 * info.channels, time, 0);
                        }
                    }
                }
                int o = codec.dequeueOutputBuffer(done, 10000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    started = true;
                } else if (o >= 0) {
                    ByteBuffer b = codec.getOutputBuffer(o);
                    if ((done.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) done.size = 0;
                    if (done.size > 0 && started) {
                        b.position(done.offset);
                        b.limit(done.offset + done.size);
                        muxer.writeSampleData(track, b, done);
                    }
                    codec.releaseOutputBuffer(o, false);
                    if ((done.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } finally {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            codec.release();
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                }
                muxer.release();
            }
        }
    }

    static void readFully(InputStream in, byte[] b) throws IOException {
        int n = 0;
        while (n < b.length) {
            int r = in.read(b, n, b.length - n);
            if (r < 0) throw new IOException("WAV je kratší, než hlási hlavička.");
            n += r;
        }
    }
}
