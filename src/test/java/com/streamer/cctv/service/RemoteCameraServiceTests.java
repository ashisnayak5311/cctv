package com.streamer.cctv.service;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteCameraServiceTests {

    @Test
    void recordsRemoteFramesIntoSeparateMp4() throws Exception {
        Path outputDirectory = Files.createTempDirectory("cctv-remote-test-");
        RemoteCameraService service = new RemoteCameraService(outputDirectory.toString());
        try {
            for (int frameNumber = 0; frameNumber < 5; frameNumber++) {
                service.acceptFrame("second-laptop", jpegFrame(frameNumber));
            }
        } finally {
            service.shutdown();
        }

        try {
            Path recording;
            try (var files = Files.list(outputDirectory)) {
                recording = files.findFirst().orElseThrow();
            }
            assertTrue(recording.getFileName().toString().startsWith("remote_second-laptop_"));
            assertTrue(recording.getFileName().toString().endsWith(".mp4"));
            try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(recording.toFile())) {
                grabber.start();
                assertEquals(320, grabber.getImageWidth());
                assertEquals(240, grabber.getImageHeight());
                assertTrue(grabber.grabImage() != null);
                grabber.stop();
            }
        } finally {
            try (var files = Files.walk(outputDirectory)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception exception) {
                        throw new IllegalStateException("Unable to remove test recording.", exception);
                    }
                });
            }
        }
    }

    @Test
    void rejectsNonJpegFrames() throws Exception {
        Path outputDirectory = Files.createTempDirectory("cctv-remote-test-");
        RemoteCameraService service = new RemoteCameraService(outputDirectory.toString());
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> service.acceptFrame("second-laptop", new byte[] { 1, 2, 3 }));
        } finally {
            service.shutdown();
            Files.delete(outputDirectory);
        }
    }

    private byte[] jpegFrame(int frameNumber) throws Exception {
        BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_3BYTE_BGR);
        image.setRGB(frameNumber, frameNumber, Color.BLUE.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        return output.toByteArray();
    }
}
