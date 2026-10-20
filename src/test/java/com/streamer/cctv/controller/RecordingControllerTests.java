package com.streamer.cctv.controller;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ShortBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingControllerTests {

    @Test
    void convertsWebmRecordingToH264Mp4() throws Exception {
        Path source = Files.createTempFile("cctv-test-", ".webm");
        try {
            createWebm(source);
            MockMultipartFile upload = new MockMultipartFile(
                    "recording", "recording.webm", "video/webm", Files.readAllBytes(source));

            var response = new RecordingController().convertToMp4(upload);
            assertEquals("video/mp4", response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            StreamingResponseBody body = response.getBody();
            assertTrue(body != null);
            body.writeTo(output);
            byte[] mp4 = output.toByteArray();
            assertTrue(mp4.length > 8);
            assertEquals("ftyp", new String(mp4, 4, 4, java.nio.charset.StandardCharsets.US_ASCII));

            Path converted = Files.createTempFile("cctv-test-converted-", ".mp4");
            try {
                Files.write(converted, mp4);
                try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(converted.toFile())) {
                    grabber.start();
                    assertEquals(1, grabber.getAudioChannels());
                    assertTrue(grabber.grabSamples() != null);
                    grabber.stop();
                }
            } finally {
                Files.deleteIfExists(converted);
            }
        } finally {
            Files.deleteIfExists(source);
        }
    }

    private void createWebm(Path destination) throws Exception {
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(destination.toFile(), 320, 240, 1)) {
            recorder.setFormat("webm");
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_VP8);
            recorder.setFrameRate(15);
            recorder.setAudioChannels(1);
            recorder.setSampleRate(16_000);
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_OPUS);
            recorder.start();

            Java2DFrameConverter converter = new Java2DFrameConverter();
            BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_3BYTE_BGR);
            for (int frameNumber = 0; frameNumber < 15; frameNumber++) {
                image.setRGB(frameNumber, frameNumber, Color.BLUE.getRGB());
                Frame frame = converter.convert(image);
                recorder.record(frame);
                short[] samples = new short[1_066];
                for (int sample = 0; sample < samples.length; sample++) {
                    samples[sample] = (short) (Math.sin((frameNumber * samples.length + sample) * 2 * Math.PI * 440 / 16_000) * 8_000);
                }
                recorder.recordSamples(16_000, 1, ShortBuffer.wrap(samples));
            }
        }
    }
}
