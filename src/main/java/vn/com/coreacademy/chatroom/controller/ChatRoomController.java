package vn.com.coreacademy.chatroom.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.chatroom.service.ChatRoomMap;
import vn.com.coreacademy.chatroom.service.ChatRoomTickets;
import vn.com.coreacademy.common.dto.BaseResponse;

@RestController
@RequestMapping("/api/v1/chat-room")
@RequiredArgsConstructor
@Tag(name = "Chat room", description = "Phòng học 3D/2D cho người dùng đã đăng nhập")
public class ChatRoomController {
    private final ChatRoomMap map;
    private final ChatRoomTickets tickets;

    @GetMapping("/map")
    @Operation(summary = "Lấy bản đồ phòng học", description = """
        Trả bố cục phòng, tốc độ và hitbox dùng chung cho client và server.
        """)
    public BaseResponse<ChatRoomMap.Layout> layout() {
        return BaseResponse.ok(map.layout());
    }

    @PostMapping("/tickets")
    @Operation(summary = "Cấp vé vào phòng học", description = """
        Cấp vé ngắn hạn dùng đúng một lần cho handshake WebSocket.
        - Cần đăng nhập và đã đổi mật khẩu tạm; tên hiển thị không dùng để xác thực.
        """)
    public BaseResponse<ChatRoomTickets.Ticket> ticket() {
        return BaseResponse.ok(tickets.issue());
    }
}
