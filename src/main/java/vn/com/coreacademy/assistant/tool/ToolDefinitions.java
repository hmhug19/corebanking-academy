package vn.com.coreacademy.assistant.tool;

import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;

/** What the model is told about each tool: name, when to use it and its arguments. No behaviour lives here. */
public final class ToolDefinitions {
    public static final String SEARCH_DOCUMENTS = "search_documents";
    public static final String SEARCH_COURSES = "search_courses";
    public static final String READ_LESSON = "read_lesson";
    public static final String SEARCH_COMMUNITY = "search_community";
    public static final String OPEN_SCREEN = "open_screen";
    public static final String UPDATE_PROFILE = "update_profile";
    public static final String OPEN_COURSE_FORM = "open_course_form";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A screen the user may open: the function code the model must send and its display name. */
    public record Screen(String code, String name) {
    }

    private ToolDefinitions() {
    }

    public static ToolDefinition searchDocuments() {
        ObjectNode parameters = object();
        parameters.putObject("properties").putObject("query").put("type", "string").put("description",
            "Câu hỏi hoặc từ khoá cần tìm, tiếng Việt, tối đa 300 ký tự.");
        parameters.putArray("required").add("query");

        return new ToolDefinition(SEARCH_DOCUMENTS,
            "Tìm trong kho tài liệu nội bộ dùng chung (quy trình, nghiệp vụ, thuật ngữ Core Banking). "
                + "Dùng cho mọi câu hỏi cần thông tin từ tài liệu. Kết quả là các đoạn đánh số [1], [2]…; "
                + "chỉ trả lời từ các đoạn đó và ghi số đoạn sau mỗi ý, ví dụ [1].", parameters);
    }

    public static ToolDefinition searchCourses() {
        ObjectNode parameters = object();
        parameters.put("additionalProperties", false);
        parameters.putObject("properties").putObject("query").put("type", "string").put("minLength", 1)
            .put("maxLength", 300).put("description", "Câu hỏi hoặc từ khoá về nội dung bài học, tiếng Việt.");
        parameters.putArray("required").add("query");

        return new ToolDefinition(SEARCH_COURSES,
            "Tìm trong nội dung các bài học của khóa học mà người dùng đang học hoặc được quản lý. Dùng khi câu hỏi "
                + "về nội dung khóa học, bài học hoặc kiến thức được dạy trong khóa. "
                + "Kết quả là các đoạn đánh số [1], [2]…; "
                + "chỉ trả lời từ các đoạn đó và ghi số đoạn sau mỗi ý, ví dụ [1].", parameters);
    }

    public static ToolDefinition readLesson() {
        ObjectNode parameters = object();
        parameters.put("additionalProperties", false);
        parameters.putObject("properties");

        return new ToolDefinition(READ_LESSON,
            "Đọc nội dung bài học mà người dùng đang xem (không cần tham số). Dùng khi người dùng muốn tóm tắt hoặc "
                + "hỏi bài này nói gì. Kết quả là các đoạn đánh số [1], [2]… theo thứ tự trong bài; "
                + "chỉ trả lời từ các đoạn đó và ghi số đoạn sau mỗi ý, ví dụ [1].", parameters);
    }

    public static ToolDefinition searchCommunity() {
        ObjectNode parameters = object();
        parameters.put("additionalProperties", false);
        parameters.putObject("properties").putObject("query").put("type", "string").put("minLength", 1)
            .put("maxLength", 300).put("description", "Từ khoá cần tìm trong cộng đồng, tiếng Việt, tối đa 300 ký tự.");
        parameters.putArray("required").add("query");

        return new ToolDefinition(SEARCH_COMMUNITY,
            "Tìm câu hỏi, bài viết, Wiki và tài liệu của cộng đồng nội bộ. Dùng khi người dùng muốn tìm hoặc xem "
                + "bài viết, thảo luận, kinh nghiệm của đồng nghiệp trong cộng đồng. Chỉ đọc các nguồn người dùng "
                + "được xem; kết quả là liên kết nguồn để mở đọc.", parameters);
    }

    /** {@code screens} are the screens of this user; the model may only name one of their codes. */
    public static ToolDefinition openScreen(List<Screen> screens) {
        ObjectNode parameters = object();
        ObjectNode code = parameters.putObject("properties").putObject("functionCode").put("type", "string");
        ArrayNode codes = code.putArray("enum");
        screens.forEach(screen -> codes.add(screen.code()));
        parameters.putArray("required").add("functionCode");

        String listing = screens.stream().map(screen -> screen.code() + " (" + screen.name() + ")")
            .collect(Collectors.joining(", "));
        return new ToolDefinition(OPEN_SCREEN,
            "Mở một màn hình của hệ thống cho người dùng. Chỉ dùng các mã sau: " + listing, parameters);
    }

    public static ToolDefinition updateProfile() {
        ObjectNode parameters = object();
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("fullName").put("type", "string").put("description", "Họ tên mới");
        properties.putObject("email").put("type", "string").put("description",
            "Email mới; chuỗi rỗng để xoá email");
        properties.putObject("positionName").put("type", "string").put("description",
            "Chức danh mới; chuỗi rỗng để xoá");

        return new ToolDefinition(UPDATE_PROFILE,
            "Đề xuất sửa hồ sơ của chính người dùng: họ tên, email, chức danh. Chỉ gửi trường người dùng muốn đổi. "
                + "Thay đổi chỉ có hiệu lực sau khi người dùng bấm xác nhận.", parameters);
    }

    public static ToolDefinition openCourseForm() {
        ObjectNode parameters = object();
        parameters.put("additionalProperties", false);
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("title").put("type", "string").put("maxLength", 200)
            .put("description", "Tên khóa học nếu người dùng đã nói. Không tự đặt.");
        properties.putObject("targetLearners").put("type", "string").put("maxLength", 500)
            .put("description", "Đối tượng học viên nếu người dùng đã nói, ví dụ: giao dịch viên mới vào làm.");
        properties.putObject("level").put("type", "string")
            .set("enum", JSON.createArrayNode().add("BEGINNER").add("INTERMEDIATE").add("ADVANCED"));
        properties.putObject("chapters").put("type", "integer").put("minimum", 1).put("maximum", 20)
            .put("description", "Số chương nếu người dùng đã nói.");
        properties.putObject("durationMinutes").put("type", "integer").put("minimum", 15).put("maximum", 1200)
            .put("description", "Tổng thời lượng mong muốn, phút, nếu người dùng đã nói.");

        return new ToolDefinition(OPEN_COURSE_FORM,
            "Mở form Khóa học mới để tạo khóa học bằng AI, điền sẵn những gì người dùng đã nói trong tin nhắn. "
                + "Gọi ngay khi người dùng muốn tạo khóa học; không hỏi thêm, không tra chương trình hay năng lực, "
                + "chỉ gửi trường người dùng đã nói.", parameters);
    }

    private static ObjectNode object() {
        return JSON.createObjectNode().put("type", "object");
    }
}
