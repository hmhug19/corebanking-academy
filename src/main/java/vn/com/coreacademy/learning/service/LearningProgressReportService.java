package vn.com.coreacademy.learning.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.constants.LearningCodes.Requirement;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningProgressOverview;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.repository.CourseRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LearningProgressReportService {
    private final LearningAccessPolicy access;
    private final CourseRepository courses;
    private final LearningSearchService search;

    public LearningProgressOverview summary() {
        var actor = access.actor();
        access.requireLearner(actor);
        var unavailable = new LearningProgressOverview.Availability("unavailable");
        var base = courses.summary(actor.person().id());
        var counts = new LearningProgressOverview.Counts(base.total(), base.completed(), base.inProgress(),
            base.notStarted(), base.overdue(), search.mineTotal(null, true),
            search.mineTotal(Requirement.MANDATORY, null));
        return new LearningProgressOverview(Instant.now(), "Asia/Ho_Chi_Minh", counts, unavailable, unavailable,
            unavailable);
    }

    public byte[] export(String q, Long fieldId, Long programId, Long moduleId, String level, String requirement,
                         String learningStatus, String state, Boolean overdue, Boolean assigned, Boolean failed,
                         String sort) {
        var rows = search.export(q, fieldId, programId, moduleId, level, requirement, learningStatus, state,
            overdue, assigned, failed, sort);
        if (rows.getTotalElements() > LearningLimits.MAX_EXPORT_ROWS) {
            throw new BusinessException(LearningResponseCode.EXPORT_TOO_LARGE);
        }
        StringBuilder csv = new StringBuilder("\uFEFFMã khóa,Tên khóa,Lần học,Trạng thái,Tiến độ,Bắt đầu,"
            + "Truy cập gần nhất,Hoàn thành,Hạn,Trạng thái hạn\r\n");
        for (var row : rows) {
            Object[] values = {row.code(), row.title(), row.cycleNo(), row.learningStatus(), row.progressPercent(),
                row.startedAt(), row.lastAccessAt(), row.completedAt(), row.dueAt(), row.deadlineStatus()};
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

    private String cell(Object value) {
        String text = value == null ? "" : value.toString();
        String trimmed = text.stripLeading();
        if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
