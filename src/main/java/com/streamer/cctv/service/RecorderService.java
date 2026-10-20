package com.streamer.cctv.service;

import jakarta.annotation.PreDestroy;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.ffmpeg.global.avcodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class RecorderService implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(RecorderService.class);
    private static final Duration CLIP_DURATION = Duration.ofMinutes(10);
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final CameraService cameraService;
    private final Path outputDirectory;
    private final Java2DFrameConverter converter = new Java2DFrameConverter();
    private volatile boolean running;
    private Thread recordingThread;
    private FFmpegFrameRecorder recorder;

    public RecorderService(
            CameraService cameraService,
            @Value("${cctv.recording.output-dir}") String outputDir) {
        this.cameraService = cameraService;
        this.outputDirectory = Path.of(outputDir).toAbsolutePath().normalize();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Files.createDirectories(outputDirectory);
        CameraService.FrameSnapshot firstFrame = cameraService.awaitNextFrame(0);
        startNewRecorder(firstFrame.image().getWidth(), firstFrame.image().getHeight());
        running = true;
        recordingThread = new Thread(() -> recordFrames(firstFrame), "cctv-auto-recorder");
        recordingThread.setDaemon(true);
        recordingThread.start();
        logger.info("Automatic camera recording started. Saving 10-minute clips to {}", outputDirectory);
    }

    private void startNewRecorder(int width, int height) throws Exception {
        String filename = "recording_" + LocalDateTime.now().format(FILE_TIMESTAMP) + ".mp4";
        Path outputFile = outputDirectory.resolve(filename);
        recorder = new FFmpegFrameRecorder(outputFile.toString(), width, height);
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        recorder.setFormat("mp4");
        recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
        recorder.setFrameRate(30);
        recorder.setVideoBitrate(5_000_000);
        recorder.setVideoOption("preset", "veryfast");
        recorder.setVideoOption("movflags", "+faststart");
        recorder.start();
    }

    private void recordFrames(CameraService.FrameSnapshot firstFrame) {
        CameraService.FrameSnapshot frame = firstFrame;
        long clipStartedAt = System.nanoTime();
        try {
            while (running) {
                Frame rawFrame = converter.convert(frame.image());
                if (rawFrame != null) {
                    recorder.record(rawFrame);
                }

                if (System.nanoTime() - clipStartedAt >= CLIP_DURATION.toNanos()) {
                    closeRecorder();
                    startNewRecorder(frame.image().getWidth(), frame.image().getHeight());
                    clipStartedAt = System.nanoTime();
                }

                frame = cameraService.awaitNextFrame(frame.sequence());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            logger.error("Automatic camera recording stopped unexpectedly.", exception);
        } finally {
            closeRecorder();
            running = false;
        }
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
            logger.error("Unable to finalize the current camera recording clip.", exception);
        } finally {
            try {
                currentRecorder.release();
            } catch (Exception exception) {
                logger.warn("Unable to release the camera recording encoder.", exception);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        Thread thread = recordingThread;
        if (thread == null) {
            closeRecorder();
            return;
        }
        thread.interrupt();
        try {
            thread.join(10_000);
            if (thread.isAlive()) {
                logger.error("Timed out waiting for the camera recording clip to finalize.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for the camera recording clip to finalize.", exception);
        }
    }
}
