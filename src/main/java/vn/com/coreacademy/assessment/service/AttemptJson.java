package vn.com.coreacademy.assessment.service;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assessment.dto.AssessmentViews.OptionText;

/**
 * Đọc/ghi các cột JSONB của lượt thi với kiểu phần tử tường minh. Dữ liệu do backend tự ghi nên lỗi parse là lỗi
 * hệ thống, không phải lỗi đầu vào của học viên.
 */
@Component
@RequiredArgsConstructor
public class AttemptJson {
    private static final TypeReference<List<Long>> IDS = new TypeReference<>() {
    };
    private static final TypeReference<List<OptionText>> OPTIONS = new TypeReference<>() {
    };

    private final ObjectMapper json;

    public List<Long> ids(String value) {
        return read(value, IDS);
    }

    public List<OptionText> options(String value) {
        return read(value, OPTIONS);
    }

    public String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize attempt snapshot", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Corrupted attempt snapshot", exception);
        }
    }
}
