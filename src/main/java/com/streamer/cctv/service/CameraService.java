package com.streamer.cctv.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.awt.Graphics2D;

@Service
public class CameraService {
    private static final Logger logger = LoggerFactory.getLogger(CameraService.class);

    private final OpenCVFrameGrabber grabber;
    private final Java2DFrameConverter converter;
    private final Thread captureThread;
    private final int requestedWidth;
    private final int requestedHeight;
    private BufferedImage latestImage;
    private long latestSequence;
    private Exception captureFailure;
    private boolean stopped;

    public CameraService(
            @Value("${cctv.camera.width:3840}") int requestedWidth,
            @Value("${cctv.camera.height:2160}") int requestedHeight) throws Exception {
        if (requestedWidth < 1 || requestedHeight < 1) {
            throw new IllegalArgumentException("Camera width and height must be positive values.");
        }
        this.requestedWidth = requestedWidth;
        this.requestedHeight = requestedHeight;
        grabber = new OpenCVFrameGrabber(0); // 0 = default webcam
        grabber.setImageWidth(requestedWidth);
        grabber.setImageHeight(requestedHeight);
        grabber.setFrameRate(30);
        grabber.start();
        converter = new Java2DFrameConverter();
        captureThread = new Thread(this::captureFrames, "cctv-camera-capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    public synchronized FrameSnapshot awaitNextFrame(long afterSequence) throws InterruptedException {
        while (latestSequence <= afterSequence && captureFailure == null && !stopped) {
            wait();
        }
        if (captureFailure != null) {
            throw new IllegalStateException("Camera capture failed.", captureFailure);
        }
        if (stopped) {
            throw new IllegalStateException("Camera capture has stopped.");
        }
        return new FrameSnapshot(latestSequence, latestImage);
    }

    private void captureFrames() {
        try {
            Frame frame;
            while (!Thread.currentThread().isInterrupted() && (frame = grabber.grab()) != null) {
                BufferedImage image = converter.convert(frame);
                if (image != null) {
                    if (latestSequence == 0) {
                        logger.info("Camera capture is {}x{} (requested {}x{}).",
                                image.getWidth(), image.getHeight(),
                                requestedWidth, requestedHeight);
                        if (image.getWidth() < requestedWidth || image.getHeight() < requestedHeight) {
                            logger.warn("The camera or driver did not provide the requested resolution; "
                                    + "using its actual {}x{} output.", image.getWidth(), image.getHeight());
                        }
                    }
                    publish(copyImage(image));
                }
            }
            synchronized (this) {
                if (!stopped) {
                    captureFailure = new IllegalStateException("The camera stopped returning frames.");
                }
                notifyAll();
            }
        } catch (Exception exception) {
            synchronized (this) {
                if (!stopped) {
                    captureFailure = exception;
                    logger.error("Camera capture stopped unexpectedly.", exception);
                }
                notifyAll();
            }
        }
    }

    private synchronized void publish(BufferedImage image) {
        latestImage = image;
        latestSequence++;
        notifyAll();
    }

    private BufferedImage copyImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    @PreDestroy
    public void shutdown() {
        synchronized (this) {
            stopped = true;
            notifyAll();
        }
        captureThread.interrupt();
        try {
            grabber.stop();
        } catch (Exception exception) {
            logger.warn("Unable to stop the camera grabber cleanly.", exception);
        } finally {
            try {
                grabber.release();
            } catch (Exception exception) {
                logger.warn("Unable to release the camera grabber cleanly.", exception);
            }
        }
    }

    public record FrameSnapshot(long sequence, BufferedImage image) {
    }
}
