package com.streamer.cctv.controller;

import com.streamer.cctv.service.RemoteCameraService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletResponse;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/cameras/{cameraId}")
public class RemoteCameraController {
    private static final Pattern CAMERA_ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private final RemoteCameraService cameraService;

    public RemoteCameraController(RemoteCameraService cameraService) {
        this.cameraService = cameraService;
    }

    @PostMapping(path = "/frame", consumes = "image/jpeg")
    public void receiveFrame(@PathVariable String cameraId, @RequestBody byte[] frame) throws IOException {
        validateCameraId(cameraId);
        try {
            cameraService.acceptFrame(cameraId, frame);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @org.springframework.web.bind.annotation.GetMapping(
            path = "/stream", produces = "multipart/x-mixed-replace; boundary=frame")
    public void stream(@PathVariable String cameraId, HttpServletResponse response) throws Exception {
        validateCameraId(cameraId);
        response.setContentType("multipart/x-mixed-replace; boundary=frame");
        OutputStream out = response.getOutputStream();
        long sequence = 0;
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG encoder is available.");
        }

        ImageWriter writer = writers.next();
        try {
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                writeParam.setCompressionQuality(0.85f);
            }
            while (!Thread.currentThread().isInterrupted()) {
                RemoteCameraService.FrameSnapshot snapshot =
                        cameraService.awaitNextFrame(cameraId, sequence);
                sequence = snapshot.sequence();
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                try (ImageOutputStream imageOut = ImageIO.createImageOutputStream(jpeg)) {
                    if (imageOut == null) {
                        throw new IOException("Unable to create JPEG output.");
                    }
                    writer.setOutput(imageOut);
                    writer.write(null, new IIOImage(snapshot.image(), null, null), writeParam);
                    imageOut.flush();
                } finally {
                    writer.setOutput(null);
                }
                out.write("--frame\r\nContent-Type: image/jpeg\r\n\r\n".getBytes());
                jpeg.writeTo(out);
                out.write("\r\n".getBytes());
                out.flush();
            }
        } finally {
            writer.dispose();
        }
    }

    private void validateCameraId(String cameraId) {
        if (!CAMERA_ID.matcher(cameraId).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Camera IDs may contain only letters, numbers, '_' or '-'.");
        }
    }
}
