package vn.com.coreacademy.storage.web;

import java.util.List;

import org.springframework.http.HttpRange;

/** Một byte range cho streaming; không có Range là toàn file, không hỗ trợ multipart/byteranges. */
record ByteRange(long offset, long length, boolean partial) {
    public static ByteRange parse(String header, long total) {
        if (total < 0) {
            throw new IllegalArgumentException("Invalid object size");
        }
        if (header == null) {
            return new ByteRange(0, total, false);
        }
        List<HttpRange> ranges = HttpRange.parseRanges(header);
        if (ranges.size() != 1 || total == 0) {
            throw new IllegalArgumentException("Exactly one satisfiable byte range is required");
        }
        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(total);
        long end = range.getRangeEnd(total);
        if (start < 0 || start >= total || end < start || end >= total) {
            throw new IllegalArgumentException("Byte range is not satisfiable");
        }
        return new ByteRange(start, end - start + 1, true);
    }

    public String contentRange(long total) {
        return "bytes " + offset + "-" + (offset + length - 1) + "/" + total;
    }
}
