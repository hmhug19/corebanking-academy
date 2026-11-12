package vn.com.coreacademy.assistant.course;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.com.coreacademy.assessment.dto.AssessmentRequests.QuestionRequest;
import vn.com.coreacademy.assessment.service.QuestionService;
import vn.com.coreacademy.assistant.course.CourseJobRepository.JobRow;
import vn.com.coreacademy.assistant.course.CourseJobRepository.StepRow;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.service.LearningAiDraftService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What accepting and rejecting a proposed question does to the job's stored proposals and to the question bank. */
class CourseDraftServiceTest {
    private static final String PROPOSALS = """
        {"questions":[
          {"type":"SINGLE","content":"Câu 1?","difficulty":"EASY","explanation":"Vì sao",
           "options":[{"content":"A","correct":true},{"content":"B","correct":false}]},
          {"type":"SINGLE","content":"Câu 2?","difficulty":"MEDIUM",
           "options":[{"content":"C","correct":true},{"content":"D","correct":false}]}]}
        """;

    private final CourseJobRepository jobs = mock(CourseJobRepository.class);
    private final CourseJobService owned = mock(CourseJobService.class);
    private final QuestionService questions = mock(QuestionService.class);
    private final ObjectMapper json = new ObjectMapper();
    private CourseDraftService service;
    private String stored;

    @BeforeEach
    void setUp() {
        service = new CourseDraftService(jobs, owned, mock(LearningAiDraftService.class), questions, json);
        JobRow job = new JobRow(7, "u1", 1, 70L, "{\"programmeId\":5,\"competencyId\":9}", "running", "questions", 80,
            null, null, null, null, 6L, 300L);
        when(owned.owned(7)).thenReturn(job);
        when(jobs.lockJob(7)).thenReturn(Optional.of(job));
        stored = PROPOSALS;
        when(jobs.stepByKey(7, CourseGenerationRunner.QUESTIONS, "c1l1")).thenAnswer(call -> Optional.of(
            new StepRow(11, 7, CourseGenerationRunner.QUESTIONS, "c1l1", null, stored, "done", 1, null)));
        when(jobs.stepsOfKind(anyLong(), anyString())).thenReturn(java.util.List.of());
        org.mockito.Mockito.doAnswer(call -> {
            stored = call.getArgument(1);
            return null;
        }).when(jobs).setOutput(org.mockito.ArgumentMatchers.eq(11L), anyString());
    }

    private String decisionOf(int index) throws Exception {
        JsonNode list = json.readTree(stored).path("questions");
        return list.get(index).path("decision").asText("");
    }

    @Test
    void acceptingAProposalWritesItToTheBankOnceWithTheChosenProgrammeAndCompetency() throws Exception {
        service.decide(7, "c1l1", 0, "accepted");
        service.decide(7, "c1l1", 0, "accepted");

        ArgumentCaptor<QuestionRequest> request = ArgumentCaptor.forClass(QuestionRequest.class);
        verify(questions, org.mockito.Mockito.times(1)).create(request.capture());
        assertThat(request.getValue().content()).isEqualTo("Câu 1?");
        assertThat(request.getValue().programId()).isEqualTo(5L);
        assertThat(request.getValue().competencyId()).isEqualTo(9L);
        assertThat(decisionOf(0)).isEqualTo("added");
        assertThat(decisionOf(1)).isEmpty();
    }

    @Test
    void rejectingAProposalKeepsItOutOfTheBankAndIsRemembered() throws Exception {
        service.decide(7, "c1l1", 1, "rejected");

        verify(questions, never()).create(org.mockito.ArgumentMatchers.any());
        assertThat(decisionOf(1)).isEqualTo("rejected");
    }

    @Test
    void aProposalAlreadyInTheBankCannotBeRejectedOrSetBackToPending() throws Exception {
        service.decide(7, "c1l1", 0, "accepted");

        assertThatThrownBy(() -> service.decide(7, "c1l1", 0, "rejected")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.decide(7, "c1l1", 0, "pending")).isInstanceOf(BusinessException.class);
        assertThat(decisionOf(0)).isEqualTo("added");
    }

    @Test
    void aRejectedProposalCanStillBeAccepted() throws Exception {
        service.decide(7, "c1l1", 1, "rejected");
        service.decide(7, "c1l1", 1, "accepted");

        verify(questions, org.mockito.Mockito.times(1)).create(org.mockito.ArgumentMatchers.any());
        assertThat(decisionOf(1)).isEqualTo("added");
    }
}
