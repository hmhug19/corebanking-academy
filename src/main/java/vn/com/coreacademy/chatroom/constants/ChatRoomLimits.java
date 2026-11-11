package vn.com.coreacademy.chatroom.constants;

public final class ChatRoomLimits {
    public static final int NAME_LENGTH = 24;
    public static final int MESSAGE_LENGTH = 280;
    public static final int CHAT_HISTORY_LENGTH = 50;
    public static final int MAX_PLAYERS = 60;
    public static final int PACKET_BYTES = 4096;
    public static final int OUTBOUND_BUFFER_BYTES = 65536;
    public static final int PACKETS_PER_SECOND = 80;
    public static final int CHAT_INTERVAL_MILLIS = 750;
    public static final int GESTURE_INTERVAL_MILLIS = 1200;
    public static final int WAVE_RANGE_PIXELS = 220;
    public static final int HIGH_FIVE_RANGE_PIXELS = 145;
    public static final int HIGH_FIVE_MIN_PIXELS = 72;
    public static final int HIGH_FIVE_INVITE_MILLIS = 10000;
    public static final int HIGH_FIVE_LOCK_MILLIS = 950;

    private ChatRoomLimits() {
    }
}
