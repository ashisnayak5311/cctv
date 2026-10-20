package com.streamer.cctv.controller;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

@RestController
public class RecordingController {

    @PostMapping(path = "/recordings/mp4", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StreamingResponseBody> convertToMp4(
            @RequestParam("recording") MultipartFile recording) throws IOException {
        if (recording == null || recording.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "The uploaded recording is empty.");
        }

        Path input = Files.createTempFile("cctv-recording-", ".webm");
        Path output = Files.createTempFile("cctv-recording-", ".mp4");
        input.toFile().deleteOnExit();
        output.toFile().deleteOnExit();
        try {
            recording.transferTo(input);
            convert(input, output);
        } catch (RuntimeException | IOException exception) {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
            throw exception;
        }

        long contentLength = Files.size(output);
        StreamingResponseBody body = response -> {
            try (var inputStream = new FileSystemResource(output).getInputStream()) {
                inputStream.transferTo(response);
            } finally {
                Files.deleteIfExists(output);
            }
        };
        Files.deleteIfExists(input);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .contentLength(contentLength)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cctv-recording.mp4\"")
                .body(body);
    }

    private void convert(Path input, Path output) throws IOException {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(input.toFile())) {
            grabber.start();
            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            if (width < 1 || height < 1) {
                throw new ResponseStatusException(UNPROCESSABLE_ENTITY, "The uploaded file contains no video.");
            }

            double frameRate = grabber.getFrameRate();
            if (!Double.isFinite(frameRate) || frameRate < 1 || frameRate > 120) {
                frameRate = 30;
            }
            int audioChannels = grabber.getAudioChannels();
            int audioSampleRate = grabber.getSampleRate();

            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(
                    output.toFile(), width, height, 0)) {
                recorder.setFormat("mp4");
                recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
                recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
                recorder.setFrameRate(frameRate);
                recorder.setVideoBitrate(4_000_000);
                recorder.setVideoOption("preset", "veryfast");
                recorder.setVideoOption("movflags", "+faststart");
                if (audioChannels > 0 && audioSampleRate > 0) {
                    recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
                    recorder.setAudioChannels(audioChannels);
                    recorder.setSampleRate(audioSampleRate);
                    recorder.setAudioBitrate(128_000);
                }
                recorder.start();

                Frame frame;
                while ((frame = grabber.grab()) != null) {
                    if (frame.image != null || frame.samples != null) {
                        recorder.record(frame);
                    }
                }
                recorder.stop();
            }
            grabber.stop();
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(
                    UNPROCESSABLE_ENTITY, "The recording could not be converted to MP4.", exception);
        }
    }
}
