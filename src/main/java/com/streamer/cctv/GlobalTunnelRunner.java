package com.streamer.cctv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Global mode is deliberately isolated from the default local profile.
@Component
@Profile("global")
public class GlobalTunnelRunner implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(GlobalTunnelRunner.class);
    private static final Pattern TUNNEL_URL =
            Pattern.compile("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com");

    @Value("${server.port:8080}")
    private int serverPort;

    private Process tunnelProcess;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String executable = System.getenv().getOrDefault("CLOUDFLARED_PATH", "cloudflared");
        try {
            tunnelProcess = new ProcessBuilder(
                    executable, "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:" + serverPort)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Global mode needs cloudflared installed and available on PATH, "
                            + "or set CLOUDFLARED_PATH to its executable.", exception);
        }

        CountDownLatch tunnelUrlReady = new CountDownLatch(1);
        AtomicReference<String> tunnelUrl = new AtomicReference<>();
        Thread outputReader = new Thread(
                () -> readTunnelOutput(tunnelProcess, tunnelUrl, tunnelUrlReady),
                "cloudflare-tunnel-output");
        outputReader.setDaemon(true);
        outputReader.start();

        if (!tunnelUrlReady.await(45, TimeUnit.SECONDS)) {
            if (!tunnelProcess.isAlive()) {
                throw new IllegalStateException("cloudflared exited before creating a public tunnel.");
            }
            throw new IllegalStateException("Timed out waiting for the Cloudflare Quick Tunnel URL.");
        }
        logger.info("Global CCTV stream is available at {}", tunnelUrl.get());
        logger.warn("This temporary public URL grants access to anyone with the URL and global login.");
    }

    private void readTunnelOutput(
            Process process, AtomicReference<String> tunnelUrl, CountDownLatch tunnelUrlReady) {
        try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = output.readLine()) != null) {
                logger.info("[cloudflared] {}", line);
                Matcher matcher = TUNNEL_URL.matcher(line);
                if (matcher.find() && tunnelUrl.compareAndSet(null, matcher.group())) {
                    tunnelUrlReady.countDown();
                }
            }
        } catch (IOException exception) {
            if (process.isAlive()) {
                logger.error("Unable to read Cloudflare Tunnel output.", exception);
            }
        }
    }

    @PreDestroy
    public void stopTunnel() {
        if (tunnelProcess != null && tunnelProcess.isAlive()) {
            tunnelProcess.destroy();
            try {
                if (!tunnelProcess.waitFor(5, TimeUnit.SECONDS)) {
                    tunnelProcess.destroyForcibly();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                tunnelProcess.destroyForcibly();
            }
        }
    }
}
