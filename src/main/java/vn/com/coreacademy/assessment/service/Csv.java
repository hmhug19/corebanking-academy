package vn.com.coreacademy.assessment.service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV cho FE tải về: UTF-8 BOM để Excel đọc đúng tiếng Việt, CRLF, mọi ô bọc nháy kép; ô bắt đầu bằng ký tự công
 * thức (=+-@) được thêm dấu ' để không thực thi khi mở bằng bảng tính. Cùng quy ước với xuất tiến độ Learning.
 */
final class Csv {
    private Csv() {
    }

    static byte[] write(List<String> headers, List<Object[]> rows) {
        StringBuilder csv = new StringBuilder("﻿").append(String.join(",", headers)).append("\r\n");
        for (Object[] values : rows) {
            for (int index = 0; index < values.length; index++) {
                if (index > 0) {
                    csv.append(',');
                }
                csv.append(cell(values[index]));
            }
            csv.append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String cell(Object value) {
        String text = value == null ? "" : value.toString();
        String trimmed = text.stripLeading();
        if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
