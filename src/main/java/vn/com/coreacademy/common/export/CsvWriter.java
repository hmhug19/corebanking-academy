package vn.com.coreacademy.common.export;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** CSV UTF-8 BOM, CRLF; vô hiệu hóa công thức bảng tính kể cả khi có khoảng trắng trước ký tự công thức. */
public final class CsvWriter {
    private CsvWriter() {
    }

    public static byte[] write(List<String> headers, List<Object[]> rows) {
        StringBuilder csv = new StringBuilder("\uFEFF").append(String.join(",", headers)).append("\r\n");
        rows.forEach(row -> append(csv, row));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void append(StringBuilder csv, Object[] values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            String text = values[i] == null ? "" : values[i].toString();
            String trimmed = text.stripLeading();
            if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) {
                text = "'" + text;
            }
            csv.append('"').append(text.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }
}
