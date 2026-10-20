package com.streamer.cctv.controller;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ServerControlController {
    private final ConfigurableApplicationContext applicationContext;

    public ServerControlController(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrfToken(CsrfToken csrfToken) {
        return Map.of("token", csrfToken.getToken());
    }

    @PostMapping("/shutdown")
    public ResponseEntity<Void> shutdown() {
        Thread shutdownThread = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            applicationContext.close();
        }, "cctv-server-shutdown");
        shutdownThread.start();
        return ResponseEntity.accepted().build();
    }
}
