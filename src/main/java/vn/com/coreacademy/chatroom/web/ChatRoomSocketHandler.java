package vn.com.coreacademy.chatroom.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import vn.com.coreacademy.chatroom.service.ChatRoomService;

@Component
@RequiredArgsConstructor
public class ChatRoomSocketHandler extends TextWebSocketHandler {
    private final ChatRoomService room;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        room.connect(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        room.receive(session, message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        room.disconnect(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        session.close(CloseStatus.SERVER_ERROR);
    }
}
