package vn.com.coreacademy.chatroom.constants;

public final class ChatRoomLimits {
    public static final int NAME_LENGTH = 24;
    public static final int MESSAGE_LENGTH = 280;
    public static final int MAX_PLAYERS = 60;
    public static final int HISTORY_SIZE = 100;
    public static final int PACKET_BYTES = 4096;
    public static final int OUTBOUND_BUFFER_BYTES = 65536;
    public static final int PACKETS_PER_SECOND = 80;
    public static final int CHAT_INTERVAL_MILLIS = 750;

    private ChatRoomLimits() {
    }
}
