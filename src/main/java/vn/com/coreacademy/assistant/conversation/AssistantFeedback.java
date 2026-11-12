package vn.com.coreacademy.assistant.conversation;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.com.coreacademy.common.persistence.UpdatedEntity;

/** Đánh giá 👍/👎 của chủ hội thoại cho một lượt; khoá là id lượt nên mỗi lượt chỉ một đánh giá, sửa được. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "assistant_feedback", schema = "learning_hub")
public class AssistantFeedback extends UpdatedEntity {
    @Id
    @Column(name = "turn_id", nullable = false)
    private Long turnId;

    @Column(name = "owner_id", length = 20, nullable = false)
    private String ownerId;

    @Column(name = "helpful", nullable = false)
    private boolean helpful;

    @Column(name = "reason_code", length = 20)
    private String reasonCode;

    @Column(name = "note", length = 2000)
    private String note;

    /** The user ticked "send the question and answer to the development team"; only with a thumbs down. */
    @Column(name = "shared", nullable = false)
    private boolean shared;

    @Column(name = "shared_time")
    private Instant sharedTime;

    // The copy below belongs to this feedback row, not to the conversation: all of it is null unless shared.
    @Column(name = "shared_question", columnDefinition = "text")
    private String sharedQuestion;

    @Column(name = "shared_answer", columnDefinition = "text")
    private String sharedAnswer;

    @Column(name = "shared_citations")
    @JdbcTypeCode(SqlTypes.JSON)
    private String sharedCitations;

    @Column(name = "shared_skill", length = 40)
    private String sharedSkill;

    @Column(name = "shared_prompt_version", length = 40)
    private String sharedPromptVersion;

    @Column(name = "shared_retrieval_trace")
    @JdbcTypeCode(SqlTypes.JSON)
    private String sharedRetrievalTrace;

    /** Copies the rated turn into this row. */
    public void share(AssistantTurn turn, Instant now) {
        shared = true;
        sharedTime = now;
        sharedQuestion = turn.getQuestion();
        sharedAnswer = turn.getAnswer();
        sharedCitations = turn.getCitations();
        sharedSkill = turn.getSkill();
        sharedPromptVersion = turn.getPromptVersion();
        sharedRetrievalTrace = turn.getRetrievalTrace();
    }

    /** Withdraws the copy: nothing of the turn stays in this row. */
    public void unshare() {
        shared = false;
        sharedTime = null;
        sharedQuestion = null;
        sharedAnswer = null;
        sharedCitations = null;
        sharedSkill = null;
        sharedPromptVersion = null;
        sharedRetrievalTrace = null;
    }
}
