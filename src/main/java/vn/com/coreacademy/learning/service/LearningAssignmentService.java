package vn.com.coreacademy.learning.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.identity.service.LearningIdentityService.Person;
import vn.com.coreacademy.learning.constants.LearningCodes.AssignmentStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.AuditAction;
import vn.com.coreacademy.learning.constants.LearningCodes.CourseStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.EnrollmentMode;
import vn.com.coreacademy.learning.constants.LearningCodes.EnrollmentOrigin;
import vn.com.coreacademy.learning.constants.LearningCodes.GroupStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.RecipientStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.ReminderChannel;
import vn.com.coreacademy.learning.constants.LearningCodes.ReminderStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.Requirement;
import vn.com.coreacademy.learning.constants.LearningCodes.RevisionState;
import vn.com.coreacademy.learning.constants.LearningCodes.ShareStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.TargetType;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningDetails;
import vn.com.coreacademy.learning.dto.LearningRequests;
import vn.com.coreacademy.learning.dto.LearningViews;
import vn.com.coreacademy.learning.entity.AssignmentRecipient;
import vn.com.coreacademy.learning.entity.AssignmentTarget;
import vn.com.coreacademy.learning.entity.Course;
import vn.com.coreacademy.learning.entity.CourseAssignment;
import vn.com.coreacademy.learning.entity.CourseEnrollment;
import vn.com.coreacademy.learning.entity.CourseRevision;
import vn.com.coreacademy.learning.entity.CourseShare;
import vn.com.coreacademy.learning.entity.LearningGroup;
import vn.com.coreacademy.learning.entity.LearningGroupMember;
import vn.com.coreacademy.learning.entity.LearningReminder;
import vn.com.coreacademy.learning.entity.RecipientTargetSource;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.mapper.LearningMapper;
import vn.com.coreacademy.learning.repository.CourseAssignmentRepository;
import vn.com.coreacademy.learning.repository.LearningGroupRepository;
import vn.com.coreacademy.learning.repository.LearningRepository;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningAssignmentService {
    private final LearningRepository repository;
    private final LearningGroupRepository groups;
    private final CourseAssignmentRepository assignments;
    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;
    private final LearningWriteLock lock;
    private final LearningContentService content;
    private final LearningMapper mapper;
    private final IdentityAuditService audit;
    private final ObjectMapper json;

    public PageResponse<LearningViews.LearningGroupView> groups(Long orgId, int page, int size) {
        var actor = access.actor();
        access.requireManage(actor, orgId, null, null);
        return PageResponse.from(groups.findByOwnerOrgId(orgId,
            PageQuery.of(page, size, "id,asc", Set.of("id"))).map(mapper::view));
    }

    public LearningDetails.GroupDetail group(Long id) {
        var actor = access.actor();
        LearningGroup group = repository.require(LearningGroup.class, id);
        access.requireManage(actor, group.getOwnerOrgId(), null, null);
        return groupDetail(group);
    }

    @Transactional
    public LearningDetails.GroupDetail createGroup(LearningRequests.GroupRequest request) {
        // Bước 1: nhóm học không tạo role; chỉ người quản lý phạm vi tổ chức được gom thành viên.
        var actor = access.actor();
        access.requireManage(actor, request.ownerOrgId(), null, null);
        lock.acquire();
        if (groups.existsByOwnerOrgIdAndCodeIgnoreCase(request.ownerOrgId(), request.code().strip())) {
            throw new BusinessException(LearningResponseCode.DUPLICATE);
        }
        LearningGroup group = new LearningGroup();
        group.setOwnerOrgId(request.ownerOrgId());
        group.setCode(request.code().strip());
        group.setName(request.name().strip());
        group.setDescription(request.description());
        group.setStatus(GroupStatus.ACTIVE);
        repository.save(group, actor.person().id());
        audit.record(actor.person().id(), AuditAction.GROUP_CREATED, TargetType.LEARNING_GROUP,
            group.getId().toString(),
            group.getOwnerOrgId(), Map.of());
        repository.flush();
        return groupDetail(group);
    }

    @Transactional
    public LearningDetails.GroupDetail members(Long id, LearningRequests.MembersRequest request) {
        var actor = access.actor();
        lock.acquire();
        LearningGroup group = repository.require(LearningGroup.class, id);
        access.requireManage(actor, group.getOwnerOrgId(), null, null);
        LearningContentService.version(group, request.expectedVersion());
        Set<String> desired = Set.copyOf(request.userIds());
        var selected = identity.learners(TargetType.USER, desired, Set.of(), null);
        if (selected.size() != desired.size()) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }

        // Bước 2: kiểm tra toàn bộ thành viên trước khi thay; không âm thầm bỏ người ngoài quyền.
        for (Person person : selected) {
            access.requireRecipientScope(actor, person);
            if (!group.getOwnerOrgId().equals(person.orgId())) {
                throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
            }
        }
        var existing = repository.members(id);
        Instant now = Instant.now();
        for (LearningGroupMember member : existing) {
            if (!desired.contains(member.getUserId())) {
                member.setLeftAt(now);
                repository.save(member, actor.person().id());
            }
        }
        Set<String> current = existing.stream().map(LearningGroupMember::getUserId)
            .collect(Collectors.toSet());
        for (String user : desired) {
            if (!current.contains(user)) {
                LearningGroupMember member = new LearningGroupMember();
                member.setGroupId(id);
                member.setUserId(user);
                member.setJoinedAt(now);
                member.setAddedBy(actor.person().id());
                repository.save(member, actor.person().id());
            }
        }

        // Bước 3: đổi version nhóm kể cả danh sách con; assignment cũ vẫn giữ snapshot riêng.
        repository.save(group, actor.person().id());
        repository.flush();
        // Ép tăng version khi chỉ thay bảng con để expectedVersion vẫn bảo vệ lần thay thành viên tiếp theo.
        repository.incrementVersion(group);
        audit.record(actor.person().id(), AuditAction.GROUP_MEMBERS_CHANGED, TargetType.LEARNING_GROUP, id.toString(),
            group.getOwnerOrgId(), Map.of("memberCount", desired.size()));
        repository.flush();
        return groupDetail(group);
    }

    public List<LearningViews.CourseShareView> shares(Long courseId) {
        var actor = access.actor();
        access.requireManage(actor, repository.require(Course.class, courseId));
        return repository.shares(courseId).stream().map(mapper::view).toList();
    }

    @Transactional
    public LearningViews.CourseShareView share(Long courseId, LearningRequests.ShareRequest request) {
        var actor = access.actor();
        lock.acquire();
        Course course = repository.require(Course.class, courseId);
        access.requireManage(actor, course);
        identity.activeOrg(request.orgId());
        if (course.getOwnerOrgId().equals(request.orgId())) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        var value = repository.shares(courseId).stream().filter(s -> s.getOrgId().equals(request.orgId()))
            .findFirst().orElseGet(CourseShare::new);
        value.setCourseId(courseId);
        value.setOrgId(request.orgId());
        value.setIncludeDescendants(request.includeDescendants());
        value.setCanAssign(request.canAssign());
        value.setCanSelfEnroll(request.canSelfEnroll());
        value.setStatus(request.active() ? ShareStatus.ACTIVE : ShareStatus.REVOKED);
        value.setSharedBy(actor.person().id());
        value.setSharedAt(Instant.now());
        value.setRevokedAt(request.active() ? null : Instant.now());
        repository.save(value, actor.person().id());
        audit.record(actor.person().id(), AuditAction.COURSE_SHARED, "course", courseId.toString(),
            course.getOwnerOrgId(), Map.of("targetOrgId", request.orgId(), "active", request.active()));
        repository.flush();
        return mapper.view(value);
    }

    public PageResponse<LearningViews.CourseAssignmentView> list(Long courseId, Long assigningOrgId, int page,
                                                                 int size) {
        var actor = access.actor();
        access.requireManage(actor, assigningOrgId, null, null);
        Course course = repository.require(Course.class, courseId);
        // Đọc lịch sử không đồng nghĩa đơn vị hiện tại được phép tạo lượt giao mới cho khóa khác đơn vị.
        if (!access.canManage(actor, course.getOwnerOrgId(), course.getOwnerTeamId(), course.getOwnerUserId())) {
            requireAssignCourse(actor, course, assigningOrgId);
        }
        return PageResponse.from(assignments.findByCourseIdAndAssigningOrgId(courseId, assigningOrgId,
            PageQuery.of(page, size, "id,desc", Set.of("id"))).map(mapper::view));
    }

    public LearningDetails.AssignmentDetail get(Long id) {
        CourseAssignment value = repository.require(CourseAssignment.class, id);
        requireAssignmentManager(access.actor(), value);
        return assignmentDetail(value);
    }

    /** Resolve snapshot, loại trùng và gắn enrollment trong một transaction toàn bộ thành công hoặc rollback. */
    @Transactional
    public LearningDetails.AssignmentDetail assign(Long courseId, LearningRequests.AssignmentRequest request) {
        // Bước 1: lấy actor, khóa ghi rồi kiểm tra quyền khai thác khóa và phạm vi đơn vị giao.
        var actor = access.actor();
        lock.acquire();
        Course course = repository.require(Course.class, courseId);
        requireAssignCourse(actor, course, request.assigningOrgId());
        String hash = hash(request);
        var prior = repository.idempotentAssignment(actor.person().id(), request.idempotencyKey());
        if (!prior.isEmpty()) {
            CourseAssignment existing = prior.get(0);
            if (!courseId.equals(existing.getCourseId()) || !hash.equals(existing.getRequestHash())) {
                throw new BusinessException(LearningResponseCode.CONFLICT);
            }
            return assignmentDetail(existing);
        }
        if (request.dueAt() != null && request.dueAt().isBefore(request.startAt())) {
            throw BusinessException.validation("dueAt", "OUT_OF_RANGE", "Hạn hoàn thành phải sau thời điểm mở học.");
        }
        CourseRevision revision = repository.require(CourseRevision.class, request.revisionId());
        requirePublished(course, revision);
        CourseAssignment assignment = new CourseAssignment();
        assignment.setCourseId(courseId);
        assignment.setRevisionId(revision.getId());
        assignment.setAssigningOrgId(request.assigningOrgId());
        assignment.setAssignedBy(actor.person().id());
        assignment.setTitle(request.title());
        assignment.setStartAt(request.startAt());
        assignment.setDueAt(request.dueAt());
        assignment.setRequirement(request.requirement());
        assignment.setEnrollmentMode(request.enrollmentMode());
        assignment.setReminderEnabled(request.reminderEnabled());
        assignment.setReminderDaysBefore(request.reminderDaysBefore());
        assignment.setStatus(AssignmentStatus.ACTIVE);
        assignment.setActivatedAt(Instant.now());
        assignment.setIdempotencyKey(request.idempotencyKey());
        assignment.setRequestHash(hash);
        repository.save(assignment, actor.person().id());

        // Bước 2: resolve từng target từ DB; giữ tất cả nguồn nhưng mỗi user chỉ có một recipient trong đợt.
        Map<String, Person> people = new TreeMap<>();
        Map<String, List<Long>> sources = new LinkedHashMap<>();
        for (var input : request.targets()) {
            var resolved = resolveTarget(actor, request.assigningOrgId(), input, assignment.getId());
            if (resolved.people().isEmpty() || resolved.people().size() > LearningLimits.MAX_LEARNERS_PER_ASSIGNMENT) {
                throw new BusinessException(LearningResponseCode.EMPTY_TARGET);
            }
            for (Person person : resolved.people()) {
                access.requireRecipientScope(actor, person);
                if (!identity.activeOrgIds(request.assigningOrgId(), true).contains(person.orgId())) {
                    throw new AccessDeniedException("Recipient outside assigning organization");
                }
                people.put(person.id(), person);
                sources.computeIfAbsent(person.id(), key -> new ArrayList<>()).add(resolved.target().getId());
            }
        }
        if (people.size() > LearningLimits.MAX_LEARNERS_PER_ASSIGNMENT) {
            throw new BusinessException(LearningResponseCode.EMPTY_TARGET);
        }

        // Bước 3: dùng chung lần học tương thích; giữ hạn/nghĩa vụ riêng tại từng người nhận.
        for (Person person : people.values()) {
            CourseEnrollment enrollment = enrollment(course, revision, person, request.enrollmentMode(),
                EnrollmentOrigin.ASSIGNED, actor.person().id());
            AssignmentRecipient recipient = new AssignmentRecipient();
            recipient.setAssignmentId(assignment.getId());
            recipient.setUserId(person.id());
            recipient.setEnrollmentId(enrollment.getId());
            recipient.setOrgIdSnapshot(person.orgId());
            recipient.setTeamIdSnapshot(person.teamId());
            recipient.setResolvedAt(Instant.now());
            recipient.setEffectiveStartAt(request.startAt());
            recipient.setEffectiveDueAt(request.dueAt());
            recipient.setRequirement(request.requirement());
            recipient.setStatus(RecipientStatus.ACTIVE);
            repository.save(recipient, actor.person().id());
            for (Long targetId : sources.get(person.id())) {
                RecipientTargetSource source = new RecipientTargetSource();
                source.setRecipientId(recipient.getId());
                source.setTargetId(targetId);
                repository.save(source, actor.person().id());
            }
            if (request.reminderEnabled() && request.dueAt() != null) {
                createReminder(recipient, request.reminderDaysBefore(), actor.person().id());
            }
        }

        // Bước 4: audit và commit snapshot; đổi thành viên nhóm sau đó không đổi người đã nhận.
        audit.record(actor.person().id(), AuditAction.COURSE_ASSIGNED, "course_assignment",
            assignment.getId().toString(), request.assigningOrgId(), Map.of("recipientCount", people.size()));
        repository.flush();
        return assignmentDetail(assignment);
    }

    @Transactional
    public LearningViews.AssignmentRecipientView revoke(Long recipientId, LearningRequests.RevokeRequest request) {
        var actor = access.actor();
        lock.acquire();
        AssignmentRecipient recipient = repository.require(AssignmentRecipient.class, recipientId);
        CourseAssignment assignment = repository.require(CourseAssignment.class, recipient.getAssignmentId());
        requireAssignmentManager(actor, assignment);
        if (RecipientStatus.REVOKED.equals(recipient.getStatus())) {
            return mapper.view(recipient);
        }
        // Chỉ thu hồi nghĩa vụ này; không xóa enrollment khi còn nguồn giao khác hoặc tự đăng ký.
        recipient.setStatus(RecipientStatus.REVOKED);
        recipient.setRevokedAt(Instant.now());
        recipient.setRevokeReason(request.reason());
        repository.save(recipient, actor.person().id());
        for (LearningReminder reminder : repository.reminders(recipientId)) {
            reminder.setStatus(ReminderStatus.CANCELLED);
            repository.save(reminder, actor.person().id());
        }
        audit.record(actor.person().id(), AuditAction.ASSIGNMENT_REVOKED, "assignment_recipient",
            recipientId.toString(), assignment.getAssigningOrgId(), Map.of());
        repository.flush();
        return mapper.view(recipient);
    }

    @Transactional
    public LearningViews.CourseEnrollmentView selfEnroll(Long courseId) {
        var actor = access.actor();
        access.requireLearner(actor);
        lock.acquire();
        Course course = repository.require(Course.class, courseId);
        if (course.getPublishedRevisionId() == null || !canDiscover(actor, course, true)) {
            throw new AccessDeniedException("Self enrollment denied");
        }
        CourseRevision revision = repository.require(CourseRevision.class, course.getPublishedRevisionId());
        requirePublished(course, revision);
        if (!Requirement.OPTIONAL.equals(revision.getDefaultRequirement())) {
            throw new BusinessException(LearningResponseCode.NOT_AVAILABLE);
        }
        CourseEnrollment enrollment = enrollment(course, revision, actor.person(), EnrollmentMode.CONTINUE,
            EnrollmentOrigin.SELF,
            actor.person().id());
        // Nếu đã được giao rồi tự đăng ký hợp lệ, giữ cả quyền tự học khi nghĩa vụ giao được thu hồi.
        if (enrollment.getSelfEnrolledAt() == null) {
            enrollment.setSelfEnrolledAt(Instant.now());
        }
        repository.save(enrollment, actor.person().id());
        audit.record(actor.person().id(), AuditAction.SELF_ENROLLED, "course_enrollment",
            enrollment.getId().toString(), actor.person().orgId(), Map.of());
        repository.flush();
        return mapper.view(enrollment);
    }

    public boolean canDiscover(Actor actor, Course course, boolean selfEnroll) {
        if (Objects.equals(actor.person().orgId(), course.getOwnerOrgId())) {
            return true;
        }
        return repository.shares(course.getId()).stream().anyMatch(s -> ShareStatus.ACTIVE.equals(s.getStatus())
            && (!selfEnroll || s.getCanSelfEnroll())
            && (Objects.equals(s.getOrgId(), actor.person().orgId()) || (s.getIncludeDescendants()
                && identity.activeAncestorIds(actor.person().orgId()).contains(s.getOrgId()))));
    }

    public CourseEnrollment requireOwnEnrollment(Actor actor, Long id, boolean studying) {
        access.requireLearner(actor);
        CourseEnrollment enrollment = repository.require(CourseEnrollment.class, id);
        if (!actor.person().id().equals(enrollment.getUserId())) {
            throw new AccessDeniedException("Enrollment owner denied");
        }
        Course course = repository.require(Course.class, enrollment.getCourseId());
        if (ProgressStatus.WITHDRAWN.equals(enrollment.getStatus())) {
            throw new BusinessException(LearningResponseCode.NOT_AVAILABLE);
        }
        if (studying) {
            if (!CourseStatus.ACTIVE.equals(course.getLifecycleStatus())) {
                throw new BusinessException(LearningResponseCode.NOT_AVAILABLE);
            }
            CourseRevision revision = repository.require(CourseRevision.class, enrollment.getRevisionId());
            content.requireCatalogAvailable(revision);
            boolean assigned = repository.enrollmentRecipients(id).stream()
                .anyMatch(r -> !r.getEffectiveStartAt().isAfter(Instant.now()));
            boolean self = enrollment.getSelfEnrolledAt() != null && canDiscover(actor, course, true);
            if (!assigned && !self) {
                throw new BusinessException(LearningResponseCode.NOT_AVAILABLE);
            }
        }
        return enrollment;
    }

    private CourseEnrollment enrollment(Course course, CourseRevision revision, Person person, String mode,
                                        String origin, String actor) {
        var history = repository.enrollmentHistory(course.getId(), person.id());
        var open = history.stream().filter(e -> Set.of(ProgressStatus.NOT_STARTED,
            ProgressStatus.IN_PROGRESS).contains(e.getStatus()))
            .findFirst();
        if (open.isPresent() && (EnrollmentMode.RETAKE.equals(mode) ||
            !revision.getId().equals(open.get().getRevisionId()))) {
            throw new BusinessException(LearningResponseCode.CONFLICT);
        }
        if (EnrollmentMode.CONTINUE.equals(mode)) {
            var matching = history.stream().filter(e -> !ProgressStatus.WITHDRAWN.equals(e.getStatus())
                && revision.getId().equals(e.getRevisionId())).findFirst();
            if (matching.isPresent()) {
                return matching.get();
            }
        }
        CourseEnrollment value = new CourseEnrollment();
        value.setCourseId(course.getId());
        value.setRevisionId(revision.getId());
        value.setUserId(person.id());
        value.setCycleNo(history.stream().mapToInt(CourseEnrollment::getCycleNo).max().orElse(0) + 1);
        value.setEnrollmentOrigin(origin);
        value.setSelfEnrolledAt(EnrollmentOrigin.SELF.equals(origin) ? Instant.now() : null);
        value.setOrgIdSnapshot(person.orgId());
        value.setTeamIdSnapshot(person.teamId());
        value.setEnrolledAt(Instant.now());
        value.setStatus(ProgressStatus.NOT_STARTED);
        value.setProgressPercent(BigDecimal.ZERO);
        repository.save(value, actor);
        return value;
    }

    private record Resolved(AssignmentTarget target, List<Person> people) {
    }

    private Resolved resolveTarget(Actor actor, Long assigningOrgId, LearningRequests.TargetInput input,
                                   Long assignmentId) {
        int count = (input.userId() == null ? 0 : 1) + (input.teamId() == null ? 0 : 1)
            + (input.orgId() == null ? 0 : 1) + (input.groupId() == null ? 0 : 1);
        if (count != 1 || (!TargetType.ORG.equals(input.targetType()) && input.includeDescendants())) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        boolean correctColumn = switch (input.targetType()) {
            case TargetType.USER -> input.userId() != null && !input.userId().isBlank();
            case TargetType.TEAM -> input.teamId() != null;
            case TargetType.ORG -> input.orgId() != null;
            case TargetType.LEARNING_GROUP -> input.groupId() != null;
            default -> false;
        };
        if (!correctColumn) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        AssignmentTarget target = new AssignmentTarget();
        target.setAssignmentId(assignmentId);
        target.setTargetType(input.targetType());
        target.setUserId(input.userId());
        target.setTeamId(input.teamId());
        target.setOrgId(input.orgId());
        target.setGroupId(input.groupId());
        target.setIncludeDescendants(input.includeDescendants());
        List<Person> people;
        switch (input.targetType()) {
            case TargetType.USER -> {
                Person person = identity.activePerson(input.userId());
                access.requireRecipientScope(actor, person);
                target.setTargetNameSnapshot(person.name());
                people = identity.learners(TargetType.USER, List.of(person.id()), Set.of(), null);
            }
            case TargetType.TEAM -> {
                var team = identity.activeTeam(input.teamId());
                access.requireManage(actor, team.orgId(), team.id(), null);
                target.setTargetNameSnapshot(team.name());
                people = identity.learners(TargetType.TEAM, List.of(), Set.of(team.orgId()), team.id());
            }
            case TargetType.ORG -> {
                var orgs = identity.activeOrgIds(input.orgId(), input.includeDescendants());
                for (Long org : orgs) {
                    access.requireManage(actor, org, null, null);
                }
                target.setTargetNameSnapshot(identity.activeOrg(input.orgId()));
                people = identity.learners(TargetType.ORG, List.of(), orgs, null);
            }
            case TargetType.LEARNING_GROUP -> {
                LearningGroup group = repository.require(LearningGroup.class, input.groupId());
                access.requireManage(actor, group.getOwnerOrgId(), null, null);
                if (!GroupStatus.ACTIVE.equals(group.getStatus()) || !identity.activeOrgIds(assigningOrgId, true)
                    .contains(group.getOwnerOrgId())) {
                    throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
                }
                target.setTargetNameSnapshot(group.getName());
                people = identity.learners(TargetType.USER, repository.members(group.getId()).stream()
                    .map(LearningGroupMember::getUserId).toList(), Set.of(), null);
            }
            default -> throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        repository.save(target, actor.person().id());
        return new Resolved(target, people);
    }

    private void requireAssignCourse(Actor actor, Course course, Long assigningOrgId) {
        access.requireManage(actor, assigningOrgId, actor.person().teamId(), actor.person().id());
        if (!CourseStatus.ACTIVE.equals(course.getLifecycleStatus())) {
            throw new BusinessException(LearningResponseCode.NOT_AVAILABLE);
        }
        if (course.getOwnerOrgId().equals(assigningOrgId)) {
            access.requireManage(actor, course);
            return;
        }
        boolean shared = repository.shares(course.getId()).stream().anyMatch(s ->
            ShareStatus.ACTIVE.equals(s.getStatus())
            && s.getCanAssign() && (s.getOrgId().equals(assigningOrgId) || (s.getIncludeDescendants()
                && identity.activeAncestorIds(assigningOrgId).contains(s.getOrgId()))));
        if (!shared) {
            throw new AccessDeniedException("Course assignment not shared");
        }
    }

    private void requireAssignmentManager(Actor actor, CourseAssignment assignment) {
        access.requireManage(actor, assignment.getAssigningOrgId(), null, assignment.getAssignedBy());
    }

    private void requirePublished(Course course, CourseRevision revision) {
        if (!course.getId().equals(revision.getCourseId()) || !RevisionState.PUBLISHED.equals(revision.getState())
            || !CourseStatus.ACTIVE.equals(course.getLifecycleStatus())) {
            throw new BusinessException(LearningResponseCode.NOT_AVAILABLE);
        }
        content.requireCatalogAvailable(revision);
    }

    private void createReminder(AssignmentRecipient recipient, int daysBefore, String actor) {
        LearningReminder reminder = new LearningReminder();
        reminder.setRecipientId(recipient.getId());
        Instant scheduled = recipient.getEffectiveDueAt().minus(daysBefore, ChronoUnit.DAYS);
        reminder.setScheduledAt(scheduled.isBefore(recipient.getEffectiveStartAt())
            ? recipient.getEffectiveStartAt() : scheduled);
        reminder.setDueAtSnapshot(recipient.getEffectiveDueAt());
        reminder.setChannel(ReminderChannel.IN_APP);
        reminder.setStatus(ReminderStatus.PENDING);
        reminder.setAttemptCount(0);
        reminder.setIdempotencyKey("recipient:" + recipient.getId() + ":due:" + recipient.getEffectiveDueAt());
        repository.save(reminder, actor);
    }

    private LearningDetails.GroupDetail groupDetail(LearningGroup group) {
        return new LearningDetails.GroupDetail(mapper.view(group), repository.members(group.getId()).stream()
            .map(mapper::view).toList());
    }

    private LearningDetails.AssignmentDetail assignmentDetail(CourseAssignment assignment) {
        return new LearningDetails.AssignmentDetail(mapper.view(assignment),
            repository.targets(assignment.getId()).stream().map(mapper::view).toList(),
            repository.recipients(assignment.getId()).stream().map(mapper::view).toList(),
            repository.sources(assignment.getId()).stream().map(mapper::view).toList());
    }

    private String hash(LearningRequests.AssignmentRequest request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("Cannot fingerprint assignment", exception);
        }
    }
}
