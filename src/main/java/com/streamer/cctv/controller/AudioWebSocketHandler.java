package com.streamer.cctv.controller;

import com.streamer.cctv.service.AudioBroadcastService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

@Component
public class AudioWebSocketHandler extends AbstractWebSocketHandler {
    private final AudioBroadcastService audioBroadcastService;

    public AudioWebSocketHandler(AudioBroadcastService audioBroadcastService) {
        this.audioBroadcastService = audioBroadcastService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        audioBroadcastService.addClient(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        audioBroadcastService.removeClient(session);
    }
}
