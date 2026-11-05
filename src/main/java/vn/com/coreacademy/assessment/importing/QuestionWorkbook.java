package vn.com.coreacademy.assessment.importing;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
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
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.Difficulty;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ItemStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuestionType;
import vn.com.coreacademy.assessment.constants.AssessmentLimits;
import vn.com.coreacademy.assessment.exception.AssessmentResponseCode;
import vn.com.coreacademy.common.dto.FieldViolation;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.storage.service.UploadStaging;

/**
 * Định dạng Excel nhập/xuất ngân hàng câu hỏi theo cột của mockup; không chứa quyền hoặc truy vấn database.
 * Tệp xuất dùng đúng cấu trúc tệp nhập để có thể sửa rồi nhập lại (ghi đè theo mã).
 */
@Component
@RequiredArgsConstructor
public class QuestionWorkbook {
    public static final String SHEET = "Questions";
    public static final String INSTRUCTIONS = "Instructions";
    public static final String OPTION_SEPARATOR = "|";
    public static final List<String> HEADERS = List.of("ma", "noi_dung", "loai", "phuong_an", "dap_an_dung",
        "giai_thich", "do_kho", "nhom_kien_thuc", "diem", "trang_thai");
    private static final int POINTS_COLUMN = 8;
    private static final List<String> GUIDE = List.of(
        "Hướng dẫn nhập ngân hàng câu hỏi (mỗi dòng sheet Questions là một câu hỏi, dòng 1 là tiêu đề):",
        "ma: mã câu hỏi (văn bản, tối đa 50 ký tự). Để trống để hệ thống sinh Q<ID>. Trùng mã câu đã có trong phạm vi"
            + " sẽ báo lỗi, trừ khi chọn chế độ ghi đè.",
        "noi_dung: nội dung câu hỏi (bắt buộc).",
        "loai: SINGLE (một đáp án) hoặc MULTIPLE (nhiều đáp án); chấp nhận mot/nhieu.",
        "phuong_an: 2-10 phương án, phân tách bằng ký tự |, ví dụ: Phương án 1|Phương án 2|Phương án 3.",
        "dap_an_dung: chữ cái theo thứ tự phương án, phân tách bằng |, ví dụ A hoặc A|C. SINGLE đúng 1 đáp án,"
            + " MULTIPLE ít nhất 2.",
        "giai_thich: giải thích hiển thị sau khi nộp bài (tùy chọn).",
        "do_kho: EASY, MEDIUM, HARD (chấp nhận Dễ, Trung bình, Khó); để trống = MEDIUM.",
        "nhom_kien_thuc: MÃ nhóm năng lực đang dùng (xem danh mục nhóm năng lực).",
        "diem: điểm mặc định của câu, số dương tối đa 2 chữ số thập phân; để trống = 1.",
        "trang_thai: ACTIVE hoặc INACTIVE; để trống = ACTIVE.",
        "Tối đa 1000 dòng, tệp .xlsx tối đa 5 MiB. Không dùng công thức, ô gộp, sheet hoặc cột lạ.");

    private final UploadStaging staging;

    /** Một dòng dữ liệu dạng văn bản đã trim; row là số dòng Excel (header là dòng 1). */
    public record QuestionRow(int row, String code, String content, String type, String options, String answers,
                              String explanation, String difficulty, String competency, String points,
                              String status) {
    }

    public record Parsed(List<QuestionRow> rows, List<FieldViolation> errors) {
    }

    public byte[] template() {
        try (InputStream input = new ClassPathResource("assessment/question-import-template.xlsx").getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read question import template", exception);
        }
    }

    /** Workbook gồm Instructions và Questions; dropdown cho cột mã cố định, cột còn lại định dạng văn bản. */
    public byte[] write(List<QuestionRow> rows) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            Sheet guide = workbook.createSheet(INSTRUCTIONS);
            for (int i = 0; i < GUIDE.size(); i++) {
                guide.createRow(i).createCell(0).setCellValue(GUIDE.get(i));
            }
            guide.setColumnWidth(0, 160 * 256);

            Sheet sheet = workbook.createSheet(SHEET);
            CellStyle text = workbook.createCellStyle();
            text.setDataFormat(workbook.createDataFormat().getFormat("@"));
            CellStyle header = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Row head = sheet.createRow(0);
            for (int column = 0; column < HEADERS.size(); column++) {
                Cell cell = head.createCell(column);
                cell.setCellValue(HEADERS.get(column));
                cell.setCellStyle(header);
                sheet.setDefaultColumnStyle(column, text);
                sheet.setColumnWidth(column, (column == 1 || column == 3 || column == 5 ? 50 : 16) * 256);
            }
            sheet.createFreezePane(0, 1);
            dropdown(sheet, 2, QuestionType.SINGLE, QuestionType.MULTIPLE);
            dropdown(sheet, 6, Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD);
            dropdown(sheet, 9, ItemStatus.ACTIVE, ItemStatus.INACTIVE);

            for (int i = 0; i < rows.size(); i++) {
                QuestionRow value = rows.get(i);
                Row row = sheet.createRow(i + 1);
                String[] cells = {value.code(), value.content(), value.type(), value.options(), value.answers(),
                    value.explanation(), value.difficulty(), value.competency(), value.points(), value.status()};
                for (int column = 0; column < cells.length; column++) {
                    Cell cell = row.createCell(column, CellType.STRING);
                    cell.setCellStyle(text);
                    cell.setCellValue(cells[column] == null ? "" : cells[column]);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot build question workbook", exception);
        }
    }

    /** Staging được dọn cả khi workbook hỏng; giới hạn kích thước/ZIP chặn tệp nén độc hại trước khi POI đọc. */
    public Parsed read(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        if (file.isEmpty() || file.getSize() > AssessmentLimits.MAX_IMPORT_FILE_BYTES || name == null
            || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw invalidFile("Chỉ nhận tệp .xlsx không rỗng, tối đa 5 MiB.");
        }
        try (InputStream input = file.getInputStream();
             var staged = staging.stage(input, AssessmentLimits.MAX_IMPORT_FILE_BYTES)) {
            checkArchive(staged.path());
            try (InputStream excel = Files.newInputStream(staged.path()); var workbook = new XSSFWorkbook(excel)) {
                return parse(workbook);
            } catch (IOException | POIXMLException | IllegalArgumentException exception) {
                throw invalidFile("Tệp Excel hỏng, được mã hóa hoặc không đúng định dạng mẫu.");
            }
        } catch (UploadStaging.LimitExceededException exception) {
            throw invalidFile("Tệp Excel vượt quá 5 MiB.");
        }
    }

    private Parsed parse(XSSFWorkbook workbook) {
        for (Sheet sheet : workbook) {
            if (!List.of(INSTRUCTIONS, SHEET).contains(sheet.getSheetName())) {
                throw invalidFile("Chỉ giữ các sheet Instructions và Questions của mẫu.");
            }
        }
        Sheet sheet = workbook.getSheet(SHEET);
        if (sheet == null || sheet.getRow(0) == null || sheet.getLastRowNum() > AssessmentLimits.MAX_IMPORT_ROWS
            || sheet.getNumMergedRegions() > 0) {
            throw invalidFile("Thiếu sheet Questions/tiêu đề, có ô gộp hoặc dữ liệu vượt dòng Excel 1001.");
        }
        Row header = sheet.getRow(0);
        for (int column = 0; column < HEADERS.size(); column++) {
            Cell cell = header.getCell(column);
            if (cell == null || cell.getCellType() != CellType.STRING
                || !HEADERS.get(column).equals(cell.getStringCellValue().trim())) {
                throw invalidFile("Tiêu đề cột không đúng mẫu. Vui lòng tải lại mẫu và giữ nguyên tên cột.");
            }
        }
        if (header.getLastCellNum() > HEADERS.size()) {
            throw invalidFile("Tiêu đề cột không đúng mẫu. Vui lòng tải lại mẫu và giữ nguyên tên cột.");
        }

        List<FieldViolation> errors = new ArrayList<>();
        List<QuestionRow> rows = new ArrayList<>();
        for (Row row : sheet) {
            if (row.getRowNum() == 0 || blank(row)) {
                continue;
            }
            for (Cell cell : row) {
                if (cell.getColumnIndex() >= HEADERS.size() && cell.getCellType() != CellType.BLANK) {
                    issue(errors, row, "", "UNSUPPORTED_FIELD", "Có dữ liệu ngoài các cột của mẫu.");
                    break;
                }
            }
            String[] values = new String[HEADERS.size()];
            for (int column = 0; column < HEADERS.size(); column++) {
                values[column] = column == POINTS_COLUMN ? number(row, column, errors) : text(row, column, errors);
            }
            rows.add(new QuestionRow(row.getRowNum() + 1, values[0], values[1], values[2], values[3], values[4],
                values[5], values[6], values[7], values[8], values[9]));
        }
        if (rows.isEmpty() || rows.size() > AssessmentLimits.MAX_IMPORT_ROWS) {
            throw invalidFile("Cần từ 1 đến 1000 dòng dữ liệu trong sheet Questions.");
        }
        return new Parsed(List.copyOf(rows), List.copyOf(errors));
    }

    private String text(Row row, int column, List<FieldViolation> errors) {
        Cell cell = row.getCell(column);
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "";
        }
        if (cell.getCellType() != CellType.STRING) {
            issue(errors, row, HEADERS.get(column), "INVALID_FORMAT",
                "Nhập nội dung dưới dạng văn bản; không dùng số, công thức hoặc giá trị lỗi Excel.");
            return "";
        }
        return cell.getStringCellValue().trim();
    }

    /** Điểm cho phép ô số hoặc văn bản; giữ dạng chuỗi thập phân để service kiểm phạm vi như API. */
    private String number(Row row, int column, List<FieldViolation> errors) {
        Cell cell = row.getCell(column);
        if (cell != null && cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            if (Double.isFinite(value)) {
                return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
            }
        }
        return text(row, column, errors);
    }

    private static boolean blank(Row row) {
        for (Cell cell : row) {
            if (cell.getCellType() != CellType.BLANK
                && !(cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank())) {
                return false;
            }
        }
        return true;
    }

    private static void dropdown(Sheet sheet, int column, String... values) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        var validation = helper.createValidation(helper.createExplicitListConstraint(values),
            new CellRangeAddressList(1, AssessmentLimits.MAX_IMPORT_ROWS, column, column));
        validation.setShowErrorBox(false);
        sheet.addValidationData(validation);
    }

    private static void issue(List<FieldViolation> errors, Row row, String field, String code, String message) {
        if (errors.size() < AssessmentLimits.MAX_IMPORT_ERRORS) {
            errors.add(new FieldViolation(SHEET + "[" + (row.getRowNum() + 1) + "]"
                + (field.isEmpty() ? "" : "." + field), code, message));
        }
    }

    private static void checkArchive(Path path) throws IOException {
        try (var input = new ZipInputStream(Files.newInputStream(path))) {
            long expanded = 0;
            int entries = 0;
            byte[] buffer = new byte[8192];
            while (input.getNextEntry() != null) {
                if (++entries > AssessmentLimits.MAX_IMPORT_ZIP_ENTRIES) {
                    throw invalidFile("Tệp Excel có quá nhiều thành phần bên trong.");
                }
                int count;
                while ((count = input.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > AssessmentLimits.MAX_IMPORT_EXPANDED_BYTES) {
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

    private static BusinessException invalidFile(String message) {
        return BusinessException.field(AssessmentResponseCode.IMPORT_INVALID, "file", "INVALID_FORMAT", message);
    }
}
