package com.moses.monitor.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebConfig implements WebSocketConfigurer {

    private final ChatHub hub;
    private final String frontendOrigin;

    public WebConfig(ChatHub hub, @Value("${moses.frontend-origin}") String frontendOrigin) {
        this.hub = hub;
        this.frontendOrigin = frontendOrigin;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(hub, "/ws/chat").setAllowedOrigins(frontendOrigin.split(","));
    }
}
