package vn.com.coreacademy.assessment.service;

import java.util.List;

import vn.com.coreacademy.common.export.CsvWriter;

/**
 * CSV cho FE tải về: UTF-8 BOM để Excel đọc đúng tiếng Việt, CRLF, mọi ô bọc nháy kép; ô bắt đầu bằng ký tự công
 * thức (=+-@) được thêm dấu ' để không thực thi khi mở bằng bảng tính. Cùng quy ước với xuất tiến độ Learning.
 */
final class Csv {
    private Csv() {
    }

    static byte[] write(List<String> headers, List<Object[]> rows) {
        return CsvWriter.write(headers, rows);
    }
}
