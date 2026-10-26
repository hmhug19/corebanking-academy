package vn.com.coreacademy.storage.dto;

/** Metadata nội bộ đã được service nghiệp vụ kiểm tra quyền; không trả key qua API. */
public record ObjectDownload(String key, String mimeType, String name, long size) {
}
