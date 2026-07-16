package org.example.asr.eval;

import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Provides the legacy streaming clients with a session while suppressing browser events. */
final class NoopWebSocketSession implements WebSocketSession {
    private final String id;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    NoopWebSocketSession(String id) {
        this.id = id;
    }

    @Override public String getId() { return id; }
    @Override public URI getUri() { return null; }
    @Override public HttpHeaders getHandshakeHeaders() { return HttpHeaders.EMPTY; }
    @Override public Map<String, Object> getAttributes() { return attributes; }
    @Override public Principal getPrincipal() { return null; }
    @Override public InetSocketAddress getLocalAddress() { return null; }
    @Override public InetSocketAddress getRemoteAddress() { return null; }
    @Override public String getAcceptedProtocol() { return null; }
    @Override public void setTextMessageSizeLimit(int messageSizeLimit) { }
    @Override public int getTextMessageSizeLimit() { return 0; }
    @Override public void setBinaryMessageSizeLimit(int messageSizeLimit) { }
    @Override public int getBinaryMessageSizeLimit() { return 0; }
    @Override public List<WebSocketExtension> getExtensions() { return Collections.emptyList(); }
    @Override public void sendMessage(WebSocketMessage<?> message) { }
    @Override public boolean isOpen() { return false; }
    @Override public void close() throws IOException { }
    @Override public void close(CloseStatus status) throws IOException { }
}
