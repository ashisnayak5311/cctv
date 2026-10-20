package com.streamer.cctv.controller;

import com.streamer.cctv.service.CameraService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.awt.image.BufferedImage;
import java.util.Iterator;

@RestController
public class StreamController {
    private final CameraService cameraService;

    public StreamController(CameraService cameraService) {
        this.cameraService = cameraService;
    }

    @GetMapping(value = "/stream", produces = "multipart/x-mixed-replace; boundary=frame")
    public void stream(HttpServletResponse response) throws Exception {
        response.setContentType("multipart/x-mixed-replace; boundary=frame");
        OutputStream out = response.getOutputStream();
        long sequence = 0;
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG encoder is available");
        }

        ImageWriter writer = writers.next();
        try {
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                writeParam.setCompressionQuality(0.98f);
            }

            while (!Thread.currentThread().isInterrupted()) {
                CameraService.FrameSnapshot snapshot = cameraService.awaitNextFrame(sequence);
                sequence = snapshot.sequence();
                BufferedImage img = snapshot.image();
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                ImageOutputStream imageOut = ImageIO.createImageOutputStream(jpeg);
                if (imageOut == null) {
                    throw new IOException("Unable to create JPEG output");
                }
                try (imageOut) {
                    writer.setOutput(imageOut);
                    writer.write(null, new IIOImage(img, null, null), writeParam);
                    imageOut.flush();
                } finally {
                    writer.setOutput(null);
                }

                out.write(("--frame\r\nContent-Type: image/jpeg\r\n\r\n").getBytes());
                jpeg.writeTo(out);
                out.write("\r\n".getBytes());
                out.flush();
            }
        } finally {
            writer.dispose();
        }
    }
}
