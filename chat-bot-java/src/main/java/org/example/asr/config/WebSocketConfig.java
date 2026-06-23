package org.example.asr.config;

import org.example.asr.handler.AsrWebSocketHandler;
import org.example.asr.handler.BenchWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private AsrWebSocketHandler asrWebSocketHandler;

    @Autowired
    private BenchWebSocketHandler benchWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(asrWebSocketHandler, "/asr")
                .setAllowedOrigins("*");
        registry.addHandler(benchWebSocketHandler, "/asr-bench")
                .setAllowedOrigins("*");
    }
}
