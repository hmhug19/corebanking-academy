package vn.com.coreacademy.learning.mapper;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.com.coreacademy.learning.dto.FlowModel;

/** Chuyển tài liệu Mô phỏng luồng giữa cột jsonb {@code lesson_blocks.flow_model} và {@link FlowModel}. */
public final class FlowModelJson {
    private static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private FlowModelJson() {
    }

    public static FlowModel read(JsonNode column) {
        if (column == null || column.isNull() || column.isMissingNode()) {
            return null;
        }
        return JSON.convertValue(column, FlowModel.class);
    }

    public static JsonNode column(FlowModel flow) {
        return flow == null ? null : JSON.valueToTree(flow);
    }
}
