package vn.com.coreacademy.chatroom.config;

import java.util.Arrays;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import vn.com.coreacademy.chatroom.service.ChatRoomTickets;
import vn.com.coreacademy.chatroom.web.ChatRoomSocketHandler;
import vn.com.coreacademy.common.exception.BusinessException;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties(ChatRoomProperties.class)
public class ChatRoomSocketConfig implements WebSocketConfigurer {
    private final ChatRoomSocketHandler handler;
    private final ChatRoomTickets tickets;
    private final String[] origins;

    public ChatRoomSocketConfig(ChatRoomSocketHandler handler, ChatRoomTickets tickets,
                               @Value("${app.cors.origins}") String origins) {
        this.handler = handler;
        this.tickets = tickets;
        this.origins = Arrays.stream(origins.split(",")).map(String::trim).toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/chat-room/socket").setAllowedOrigins(origins)
            .addInterceptors(new HandshakeInterceptor() {
                @Override
                public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                               WebSocketHandler socketHandler, Map<String, Object> attributes) {
                    String ticket = UriComponentsBuilder.fromUri(request.getURI()).build()
                        .getQueryParams().getFirst("ticket");
                    try {
                        attributes.put(ChatRoomTickets.ACCESS_ATTRIBUTE, tickets.consume(ticket));
                        return true;
                    } catch (BusinessException exception) {
                        response.setStatusCode(HttpStatus.UNAUTHORIZED);
                        return false;
                    }
                }

                @Override
                public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler socketHandler, Exception exception) {
                    // Không lưu vé hoặc URI handshake vào log.
                }
            });
    }
}
