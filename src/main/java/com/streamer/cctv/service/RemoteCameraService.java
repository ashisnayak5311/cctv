package com.streamer.cctv.service;

import jakarta.annotation.PreDestroy;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class RemoteCameraService {
    private static final Logger logger = LoggerFactory.getLogger(RemoteCameraService.class);
    private static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;
    private static final int MAX_WIDTH = 3840;
    private static final int MAX_HEIGHT = 2160;
    private static final Duration CLIP_DURATION = Duration.ofMinutes(10);
    private static final Duration INACTIVE_DURATION = Duration.ofSeconds(5);
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path outputDirectory;
    private final ConcurrentHashMap<String, RemoteCamera> cameras = new ConcurrentHashMap<>();
    private final ScheduledExecutorService finalizer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "cctv-remote-camera-finalizer");
        thread.setDaemon(true);
        return thread;
    });

    public RemoteCameraService(@Value("${cctv.recording.output-dir}") String outputDir) throws IOException {
        outputDirectory = Path.of(outputDir).toAbsolutePath().normalize();
        Files.createDirectories(outputDirectory);
        finalizer.scheduleAtFixedRate(this::finalizeInactiveRecorders, 1, 1, TimeUnit.SECONDS);
    }

    public void acceptFrame(String cameraId, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("A camera frame must be between 1 byte and 4 MB.");
        }
        if (bytes.length < 2 || (bytes[0] & 0xff) != 0xff || (bytes[1] & 0xff) != 0xd8) {
            throw new IllegalArgumentException("The uploaded camera frame is not a JPEG.");
        }
        BufferedImage image;
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            image = ImageIO.read(input);
        }
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1
                || image.getWidth() > MAX_WIDTH || image.getHeight() > MAX_HEIGHT) {
            throw new IllegalArgumentException("The frame must be a valid JPEG up to 3840x2160.");
        }
        RemoteCamera camera = cameras.computeIfAbsent(cameraId, RemoteCamera::new);
        camera.accept(image);
    }

    public FrameSnapshot awaitNextFrame(String cameraId, long afterSequence) throws InterruptedException {
        RemoteCamera camera = cameras.computeIfAbsent(cameraId, RemoteCamera::new);
        return camera.awaitNextFrame(afterSequence);
    }

    private void finalizeInactiveRecorders() {
        long inactiveBefore = System.nanoTime() - INACTIVE_DURATION.toNanos();
        cameras.values().forEach(camera -> camera.finalizeIfInactive(inactiveBefore));
    }

    @PreDestroy
    public void shutdown() {
        finalizer.shutdownNow();
        cameras.values().forEach(RemoteCamera::shutdown);
    }

    private final class RemoteCamera {
        private final String cameraId;
        private final Java2DFrameConverter converter = new Java2DFrameConverter();
        private BufferedImage latestImage;
        private long latestSequence;
        private long lastFrameNanos;
        private long clipStartedAt;
        private FFmpegFrameRecorder recorder;

        private RemoteCamera(String cameraId) {
            this.cameraId = cameraId;
        }

        private synchronized void accept(BufferedImage image) throws IOException {
            long now = System.nanoTime();
            if (recorder == null || image.getWidth() != recorder.getImageWidth()
                    || image.getHeight() != recorder.getImageHeight()
                    || now - clipStartedAt >= CLIP_DURATION.toNanos()) {
                closeRecorder();
                startRecorder(image.getWidth(), image.getHeight());
                clipStartedAt = now;
            }
            try {
                recorder.record(converter.convert(image));
            } catch (Exception exception) {
                closeRecorder();
                throw new IOException("Unable to record camera feed '" + cameraId + "'.", exception);
            }
            latestImage = image;
            latestSequence++;
            lastFrameNanos = now;
            notifyAll();
        }

        private synchronized FrameSnapshot awaitNextFrame(long afterSequence) throws InterruptedException {
            while (latestSequence <= afterSequence) {
                wait();
            }
            return new FrameSnapshot(latestSequence, latestImage);
        }

        private synchronized void finalizeIfInactive(long inactiveBefore) {
            if (recorder != null && lastFrameNanos < inactiveBefore) {
                closeRecorder();
            }
        }

        private void startRecorder(int width, int height) throws IOException {
            String filename = "remote_" + cameraId + "_"
                    + LocalDateTime.now().format(FILE_TIMESTAMP) + ".mp4";
            Path outputFile = outputDirectory.resolve(filename);
            FFmpegFrameRecorder nextRecorder = new FFmpegFrameRecorder(outputFile.toString(), width, height);
            try {
                nextRecorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
                nextRecorder.setFormat("mp4");
                nextRecorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
                nextRecorder.setFrameRate(10);
                nextRecorder.setVideoBitrate(3_000_000);
                nextRecorder.setVideoOption("preset", "veryfast");
                nextRecorder.setVideoOption("movflags", "+faststart");
                nextRecorder.start();
                recorder = nextRecorder;
                logger.info("Recording remote camera '{}' to {}", cameraId, outputFile);
            } catch (Exception exception) {
                try {
                    nextRecorder.release();
                } catch (Exception releaseException) {
                    exception.addSuppressed(releaseException);
                }
                throw new IOException("Unable to start recording camera feed '" + cameraId + "'.", exception);
            }
        }

        private synchronized void shutdown() {
            closeRecorder();
            converter.close();
        }

        private void closeRecorder() {
            FFmpegFrameRecorder currentRecorder = recorder;
            recorder = null;
            if (currentRecorder == null) {
                return;
            }
            try {
                currentRecorder.stop();
            } catch (Exception exception) {
                logger.error("Unable to finalize remote camera '{}' recording.", cameraId, exception);
            } finally {
                try {
                    currentRecorder.release();
                } catch (Exception exception) {
                    logger.warn("Unable to release remote camera '{}' encoder.", cameraId, exception);
                }
            }
        }
    }

    public record FrameSnapshot(long sequence, BufferedImage image) {
    }
}
