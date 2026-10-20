package com.streamer.cctv.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AudioBroadcastService {
    private static final Logger logger = LoggerFactory.getLogger(AudioBroadcastService.class);
    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(48_000, 16, 1, true, false);
    private static final int AUDIO_BUFFER_SIZE = 1_920;

    private final Object lifecycleLock = new Object();
    private final Set<WebSocketSession> clients = ConcurrentHashMap.newKeySet();
    private volatile TargetDataLine microphone;
    private Thread captureThread;

    public void addClient(WebSocketSession session) {
        clients.add(session);
        synchronized (lifecycleLock) {
            if (captureThread == null || !captureThread.isAlive()) {
                captureThread = new Thread(this::captureAndBroadcast, "cctv-microphone-capture");
                captureThread.setDaemon(true);
                captureThread.start();
            }
        }
    }

    public void removeClient(WebSocketSession session) {
        clients.remove(session);
        if (clients.isEmpty()) {
            stopCapture();
        }
    }

    private void captureAndBroadcast() {
        TargetDataLine line = null;
        boolean failed = false;
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, AUDIO_FORMAT);
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(AUDIO_FORMAT, AUDIO_BUFFER_SIZE * 4);
            microphone = line;
            line.start();

            byte[] buffer = new byte[AUDIO_BUFFER_SIZE];
            while (!Thread.currentThread().isInterrupted() && !clients.isEmpty()) {
                int bytesRead = line.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    broadcastAudio(buffer, bytesRead);
                }
            }
        } catch (Exception exception) {
            failed = !clients.isEmpty();
            if (failed) {
                logger.error("Unable to capture audio from the server's default microphone", exception);
            }
        } finally {
            if (line != null) {
                line.stop();
                line.close();
            }
            microphone = null;
            synchronized (lifecycleLock) {
                if (captureThread == Thread.currentThread()) {
                    captureThread = null;
                }
            }
            if (failed) {
                notifyMicrophoneUnavailable();
            }
        }
    }

    private void broadcastAudio(byte[] buffer, int length) {
        for (WebSocketSession client : clients) {
            if (!client.isOpen()) {
                removeClient(client);
                continue;
            }

            try {
                synchronized (client) {
                    client.sendMessage(new BinaryMessage(ByteBuffer.wrap(buffer, 0, length)));
                }
            } catch (IOException exception) {
                logger.warn("Unable to send microphone audio to viewer {}", client.getId(), exception);
                removeClient(client);
                try {
                    client.close(CloseStatus.SERVER_ERROR);
                } catch (IOException closeException) {
                    logger.warn("Unable to close failed audio connection {}", client.getId(), closeException);
                }
            }
        }
    }

    private void notifyMicrophoneUnavailable() {
        for (WebSocketSession client : clients) {
            try {
                synchronized (client) {
                    client.sendMessage(new TextMessage("ERROR:Server microphone is unavailable."));
                    client.close(CloseStatus.SERVER_ERROR);
                }
            } catch (IOException exception) {
                logger.warn("Unable to report microphone failure to viewer {}", client.getId(), exception);
            }
        }
    }

    private void stopCapture() {
        Thread thread;
        synchronized (lifecycleLock) {
            thread = captureThread;
            if (thread == null) {
                return;
            }
            thread.interrupt();
            TargetDataLine line = microphone;
            if (line != null) {
                line.stop();
                line.close();
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        clients.clear();
        stopCapture();
    }
}
