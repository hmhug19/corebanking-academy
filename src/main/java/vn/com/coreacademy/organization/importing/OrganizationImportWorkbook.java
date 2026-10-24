package vn.com.coreacademy.organization.importing;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.common.dto.FieldViolation;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.organization.exception.OrganizationResponseCode;
import vn.com.coreacademy.storage.service.UploadStaging;

/** Định dạng Excel riêng của import tổ chức; không chứa quyền hoặc truy vấn database. */
@Component
@RequiredArgsConstructor
public class OrganizationImportWorkbook {
    public static final int MAX_ROWS = 1000;
    public static final int MAX_ERRORS = 200;
    private static final long MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 20 * 1024 * 1024;
    private static final List<String> ORG_HEADERS = List.of("orgType", "code", "name", "parentCode", "sortNo");
    private static final List<String> TEAM_HEADERS = List.of("orgCode", "code", "name", "description");

    private final UploadStaging staging;

    public record OrgRow(int row, String orgType, String code, String name, String parentCode, int sortNo) {
    }

    public record TeamRow(int row, String orgCode, String code, String name, String description) {
    }

    public record Parsed(List<OrgRow> organizations, List<TeamRow> teams, List<FieldViolation> errors) {
    }

    /** Đọc bằng stream để resource dùng được cả trong executable JAR, không phụ thuộc đường dẫn local. */
    public byte[] template() throws IOException {
        var resource = new ClassPathResource("templates/organization-import-template.xlsx");
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
        }
    }

    /** Staging được dọn cả khi workbook hỏng; giới hạn ZIP riêng, không thay cấu hình POI toàn ứng dụng. */
    public Parsed read(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        if (file.isEmpty() || file.getSize() > MAX_FILE_BYTES || name == null
            || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw invalidFile("Chỉ nhận tệp .xlsx không rỗng, tối đa 5 MiB.");
        }
        try (InputStream input = file.getInputStream(); var staged = staging.stage(input)) {
            if (staged.size() > MAX_FILE_BYTES) {
                throw invalidFile("Tệp Excel vượt quá 5 MiB.");
            }
            checkArchive(staged.path());
            try (InputStream excel = Files.newInputStream(staged.path()); var workbook = new XSSFWorkbook(excel)) {
                return parse(workbook);
            } catch (IOException | POIXMLException | IllegalArgumentException exception) {
                throw invalidFile("Tệp Excel hỏng, được mã hóa hoặc không đúng định dạng mẫu.");
            }
        }
    }

    private Parsed parse(XSSFWorkbook workbook) {
        for (Sheet sheet : workbook) {
            if (!List.of("Instructions", "Organizations", "Teams").contains(sheet.getSheetName())) {
                throw invalidFile("Chỉ giữ các sheet Instructions, Organizations và Teams của mẫu.");
            }
        }
        Sheet orgs = requiredSheet(workbook, "Organizations", ORG_HEADERS);
        Sheet teams = requiredSheet(workbook, "Teams", TEAM_HEADERS);
        List<FieldViolation> errors = new ArrayList<>();
        List<OrgRow> orgRows = new ArrayList<>();
        List<TeamRow> teamRows = new ArrayList<>();
        for (Row row : orgs) {
            if (row.getRowNum() == 0 || blank(row)) {
                continue;
            }
            checkColumns(row, "Organizations", ORG_HEADERS.size(), errors);
            orgRows.add(new OrgRow(row.getRowNum() + 1,
                text(row, 0, "Organizations", "orgType", errors), text(row, 1, "Organizations", "code", errors),
                text(row, 2, "Organizations", "name", errors), text(row, 3, "Organizations", "parentCode", errors),
                sortNo(row, errors)));
        }
        for (Row row : teams) {
            if (row.getRowNum() == 0 || blank(row)) {
                continue;
            }
            checkColumns(row, "Teams", TEAM_HEADERS.size(), errors);
            teamRows.add(new TeamRow(row.getRowNum() + 1, text(row, 0, "Teams", "orgCode", errors),
                text(row, 1, "Teams", "code", errors), text(row, 2, "Teams", "name", errors),
                text(row, 3, "Teams", "description", errors)));
        }
        if (orgRows.size() + teamRows.size() == 0 || orgRows.size() + teamRows.size() > MAX_ROWS) {
            throw invalidFile("Cần từ 1 đến 1000 dòng dữ liệu tổng cộng trên hai sheet.");
        }
        return new Parsed(List.copyOf(orgRows), List.copyOf(teamRows), List.copyOf(errors));
    }

    private Sheet requiredSheet(XSSFWorkbook workbook, String name, List<String> headers) {
        Sheet sheet = workbook.getSheet(name);
        if (sheet == null || sheet.getRow(0) == null || sheet.getLastRowNum() > MAX_ROWS
            || sheet.getNumMergedRegions() > 0) {
            throw invalidFile("Thiếu sheet/header bắt buộc hoặc có dữ liệu vượt dòng Excel 1001.");
        }
        Row header = sheet.getRow(0);
        if (header.getLastCellNum() != headers.size()) {
            throw invalidFile("Header không đúng mẫu. Vui lòng tải lại template và giữ nguyên tên cột.");
        }
        for (int column = 0; column < headers.size(); column++) {
            Cell cell = header.getCell(column);
            if (cell == null || cell.getCellType() != CellType.STRING
                || !headers.get(column).equals(cell.getStringCellValue())) {
                throw invalidFile("Header không đúng mẫu. Vui lòng tải lại template và giữ nguyên tên cột.");
            }
        }
        return sheet;
    }

    private String text(Row row, int column, String sheet, String field, List<FieldViolation> errors) {
        Cell cell = row.getCell(column);
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "";
        }
        if (cell.getCellType() != CellType.STRING) {
            issue(errors, sheet, row, field, "INVALID_FORMAT",
                "Nhập nội dung dưới dạng văn bản; không dùng số, công thức hoặc giá trị lỗi Excel.");
            return "";
        }
        return cell.getStringCellValue().trim();
    }

    private int sortNo(Row row, List<FieldViolation> errors) {
        Cell cell = row.getCell(4);
        if (cell == null || cell.getCellType() == CellType.BLANK
            || (cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank())) {
            return 0;
        }
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                double number = cell.getNumericCellValue();
                if (Double.isFinite(number) && number == Math.rint(number)
                    && number >= 0 && number <= Integer.MAX_VALUE) {
                    return (int) number;
                }
            } else if (cell.getCellType() == CellType.STRING) {
                int value = Integer.parseInt(cell.getStringCellValue().trim());
                if (value >= 0) {
                    return value;
                }
            }
        } catch (NumberFormatException exception) {
            // Trả lỗi theo cột thay vì phản chiếu nội dung ô Excel.
        }
        issue(errors, "Organizations", row, "sortNo", "OUT_OF_RANGE", "Thứ tự phải là số nguyên từ 0 đến 2147483647.");
        return 0;
    }

    private boolean blank(Row row) {
        for (Cell cell : row) {
            if (cell.getCellType() != CellType.BLANK
                && !(cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank())) {
                return false;
            }
        }
        return true;
    }

    private void checkColumns(Row row, String sheet, int columns, List<FieldViolation> errors) {
        for (Cell cell : row) {
            if (cell.getColumnIndex() >= columns && cell.getCellType() != CellType.BLANK) {
                issue(errors, sheet, row, "", "UNSUPPORTED_FIELD", "Có dữ liệu ngoài các cột của mẫu.");
                return;
            }
        }
    }

    private void issue(List<FieldViolation> errors, String sheet, Row row, String field, String code, String message) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(new FieldViolation(sheet + "[" + (row.getRowNum() + 1) + "]"
                + (field.isEmpty() ? "" : "." + field), code, message));
        }
    }

    private void checkArchive(Path path) throws IOException {
        try (var input = new ZipInputStream(Files.newInputStream(path))) {
            long expanded = 0;
            int entries = 0;
            byte[] buffer = new byte[8192];
            while (input.getNextEntry() != null) {
                if (++entries > 100) {
                    throw invalidFile("Tệp Excel có quá nhiều thành phần bên trong.");
                }
                int count;
                while ((count = input.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > MAX_EXPANDED_BYTES) {
                        throw invalidFile("Nội dung Excel sau giải nén vượt quá giới hạn 20 MiB.");
                    }
                }
            }
            if (entries == 0) {
                throw invalidFile("Tệp không phải workbook .xlsx hợp lệ.");
            }
        } catch (ZipException | EOFException exception) {
            throw invalidFile("Tệp Excel hỏng hoặc không đúng định dạng .xlsx.");
        }
    }

    private BusinessException invalidFile(String message) {
        return BusinessException.field(OrganizationResponseCode.IMPORT_INVALID, "file", "INVALID_FORMAT", message);
    }
}
