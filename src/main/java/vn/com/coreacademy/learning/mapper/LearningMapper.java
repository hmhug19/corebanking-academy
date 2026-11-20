package vn.com.coreacademy.learning.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import vn.com.coreacademy.common.mapper.CentralMapperConfig;
import vn.com.coreacademy.learning.dto.LearningViews;
import vn.com.coreacademy.learning.dto.MediaViews;
import vn.com.coreacademy.learning.entity.AssignmentRecipient;
import vn.com.coreacademy.learning.entity.AssignmentTarget;
import vn.com.coreacademy.learning.entity.BlockAsset;
import vn.com.coreacademy.learning.entity.BlockProgress;
import vn.com.coreacademy.learning.entity.BusinessModule;
import vn.com.coreacademy.learning.entity.Course;
import vn.com.coreacademy.learning.entity.CourseAssessment;
import vn.com.coreacademy.learning.entity.CourseAssignment;
import vn.com.coreacademy.learning.entity.CourseChapter;
import vn.com.coreacademy.learning.entity.CourseCompletionRule;
import vn.com.coreacademy.learning.entity.CourseEnrollment;
import vn.com.coreacademy.learning.entity.CourseLesson;
import vn.com.coreacademy.learning.entity.CourseObjective;
import vn.com.coreacademy.learning.entity.CourseRevision;
import vn.com.coreacademy.learning.entity.CourseShare;
import vn.com.coreacademy.learning.entity.LearningAsset;
import vn.com.coreacademy.learning.entity.LearningGroup;
import vn.com.coreacademy.learning.entity.LearningGroupMember;
import vn.com.coreacademy.learning.entity.LearningReminder;
import vn.com.coreacademy.learning.entity.LearningUserLease;
import vn.com.coreacademy.learning.entity.LessonBlock;
import vn.com.coreacademy.learning.entity.LessonProgress;
import vn.com.coreacademy.learning.entity.ProcessStep;
import vn.com.coreacademy.learning.entity.RecipientTargetSource;
import vn.com.coreacademy.learning.entity.TrainingField;
import vn.com.coreacademy.learning.entity.TrainingProgram;
import vn.com.coreacademy.learning.entity.VideoWatchRange;

@Mapper(config = CentralMapperConfig.class)
public interface LearningMapper {
    LearningViews.TrainingFieldView view(TrainingField value);

    LearningViews.TrainingProgramView view(TrainingProgram value);

    LearningViews.BusinessModuleView view(BusinessModule value);

    LearningViews.CourseView view(Course value);

    LearningViews.CourseRevisionView view(CourseRevision value);

    LearningViews.CourseObjectiveView view(CourseObjective value);

    LearningViews.CourseChapterView view(CourseChapter value);

    LearningViews.CourseLessonView view(CourseLesson value);

    @Mapping(target = "videoChapters", expression = "java(LessonBlockJson.chapters(value.getVideoChapters()))")
    @Mapping(target = "transcript", expression = "java(LessonBlockJson.lines(value.getTranscript()))")
    @Mapping(target = "flow", expression = "java(FlowModelJson.read(value.getFlowModel()))")
    LearningViews.LessonBlockView view(LessonBlock value);

    LearningViews.ProcessStepView view(ProcessStep value);

    LearningViews.LearningAssetView view(LearningAsset value);

    LearningViews.BlockAssetView view(BlockAsset value);

    LearningViews.LearningGroupView view(LearningGroup value);

    LearningViews.LearningGroupMemberView view(LearningGroupMember value);

    LearningViews.CourseShareView view(CourseShare value);

    LearningViews.CourseAssignmentView view(CourseAssignment value);

    LearningViews.AssignmentTargetView view(AssignmentTarget value);

    LearningViews.AssignmentRecipientView view(AssignmentRecipient value);

    LearningViews.RecipientTargetSourceView view(RecipientTargetSource value);

    LearningViews.CourseEnrollmentView view(CourseEnrollment value);

    @Mapping(target = "courseId", ignore = true)
    @Mapping(target = "courseTitle", ignore = true)
    @Mapping(target = "enrollmentId", ignore = true)
    @Mapping(target = "courseAvailable", ignore = true)
    LearningViews.LearningReminderView view(LearningReminder value);

    @Mapping(target = "recipientId", source = "value.recipientId")
    @Mapping(target = "courseId", source = "course.courseId")
    @Mapping(target = "courseTitle", source = "course.courseTitle")
    @Mapping(target = "enrollmentId", source = "course.enrollmentId")
    @Mapping(target = "courseAvailable", source = "course.courseAvailable")
    LearningViews.LearningReminderView view(LearningReminder value, LearningViews.ReminderCourse course);

    LearningViews.LessonProgressView view(LessonProgress value);

    LearningViews.BlockProgressView view(BlockProgress value);

    LearningViews.VideoWatchRangeView view(VideoWatchRange value);

    LearningViews.CourseCompletionRuleView view(CourseCompletionRule value);

    LearningViews.CourseAssessmentView view(CourseAssessment value);

    @Mapping(target = "lastAcceptedSequence", source = "lastEventSequence")
    @Mapping(target = "leaseExpiresAt", source = "expiresAt")
    MediaViews.Receipt receipt(LearningUserLease value);

}
