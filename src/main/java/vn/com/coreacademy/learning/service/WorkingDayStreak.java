package vn.com.coreacademy.learning.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;

/**
 * Chuỗi ngày học chỉ đếm ngày làm việc. Ngày làm việc là thứ Hai đến thứ Sáu trừ ngày nghỉ trong lịch chung, cộng ngày
 * làm bù (thường là thứ Bảy). Ngày nghỉ không cộng vào chuỗi và cũng không làm đứt chuỗi; học vào ngày nghỉ vẫn được
 * ghi nhận ở lịch nhưng không thêm gì cho chuỗi. Một ngày đủ điều kiện khi có ít nhất một bài học hoàn thành hoặc một
 * bài kiểm tra đã nộp.
 */
public final class WorkingDayStreak {
    public static final String HOLIDAY = "HOLIDAY";
    public static final String MAKEUP_WORKDAY = "MAKEUP_WORKDAY";

    /** Chuỗi hiện tại và chuỗi dài nhất, tính theo ngày làm việc. */
    public record Result(int current, int longest) {
    }

    private WorkingDayStreak() {
    }

    /** Ngày làm việc theo lịch: ngày làm bù luôn là ngày làm việc, ngày nghỉ luôn không, còn lại theo thứ trong tuần. */
    public static boolean isWorkingDay(LocalDate day, Map<LocalDate, String> calendar) {
        String type = calendar.get(day);
        if (MAKEUP_WORKDAY.equals(type)) {
            return true;
        }
        if (HOLIDAY.equals(type)) {
            return false;
        }
        DayOfWeek week = day.getDayOfWeek();
        return week != DayOfWeek.SATURDAY && week != DayOfWeek.SUNDAY;
    }

    /**
     * Tính chuỗi tại ngày today (ngày Asia/Ho_Chi_Minh của bên gọi). Hôm nay là ngày làm việc nhưng chưa học thì chưa
     * làm đứt chuỗi: chuỗi tính từ ngày làm việc trước đó. active là tập ngày đủ điều kiện.
     */
    public static Result compute(java.util.Set<LocalDate> active, Map<LocalDate, String> calendar, LocalDate today) {
        if (active.isEmpty()) {
            return new Result(0, 0);
        }
        LocalDate earliest = active.stream().min(LocalDate::compareTo).orElseThrow();

        int current = 0;
        LocalDate day = today;
        if (isWorkingDay(day, calendar) && !active.contains(day)) {
            day = day.minusDays(1);
        }
        for (; !day.isBefore(earliest); day = day.minusDays(1)) {
            if (!isWorkingDay(day, calendar)) {
                continue;
            }
            if (!active.contains(day)) {
                break;
            }
            current++;
        }

        int longest = 0;
        int run = 0;
        for (LocalDate cursor = earliest; !cursor.isAfter(today); cursor = cursor.plusDays(1)) {
            if (!isWorkingDay(cursor, calendar)) {
                continue;
            }
            run = active.contains(cursor) ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return new Result(current, longest);
    }
}
