package vn.com.coreacademy.scorm.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.learning.service.LearningAccessPolicy;
import vn.com.coreacademy.scorm.config.ScormProperties;
import vn.com.coreacademy.scorm.dto.ScormDtos;
import vn.com.coreacademy.scorm.repository.ScormRepository;
import vn.com.coreacademy.scorm.repository.ScormRepository.PackageRow;
import vn.com.coreacademy.scorm.repository.ScormRepository.Registration;
import vn.com.coreacademy.learning.constants.LearningCodes.LessonType;
import vn.com.coreacademy.learning.entity.CourseChapter;
import vn.com.coreacademy.learning.entity.CourseEnrollment;
import vn.com.coreacademy.learning.entity.CourseLesson;
import vn.com.coreacademy.learning.repository.LearningRepository;
import vn.com.coreacademy.learning.service.LearningAssignmentService;
import vn.com.coreacademy.learning.service.LearningProgressService;
import vn.com.coreacademy.learning.service.LearningWriteLock;
import vn.com.coreacademy.security.SecurityContextUtils;
import vn.com.coreacademy.storage.dto.ObjectDownload;
import vn.com.coreacademy.storage.service.ObjectStorage;

/** SCORM library and course lessons; author previews are isolated from every learner enrollment. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScormService {
    private static final Set<String> STATUSES = Set.of("passed", "completed", "failed", "incomplete", "browsed",
        "not attempted");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ScormPackageReader reader;
    private final ScormRepository repository;
    private final ObjectStorage storage;
    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;
    private final ScormProperties properties;
    private final PlatformTransactionManager transactions;
    private final IdentityAuditService audit;
    private final LearningRepository learning;
    private final LearningAssignmentService assignments;
    private final LearningProgressService courseProgress;
    private final LearningWriteLock learningLock;

    public ScormDtos.PackageView upload(long orgId, MultipartFile file) throws IOException {
        Actor actor = access.actor();
        access.requireManage(actor, orgId, actor.person().teamId(), actor.person().id());
        if (file.getOriginalFilename() == null || file.getOriginalFilename().length() > 255) {
            throw invalid("file", "Tên tệp không được quá 255 ký tự.");
        }
        List<String> written = new ArrayList<>();
        try (var content = reader.read(file)) {
            String prefix = "scorm/" + UUID.randomUUID() + "/";
            Map<String, ScormRepository.StoredFile> files = new LinkedHashMap<>();
            try {
                for (var entry : content.files().entrySet()) {
                    var value = entry.getValue();
                    String key = prefix + ScormPackageReader.hash(entry.getKey());
                    written.add(key);
                    storage.put(key, value.file(), value.mime(), value.checksum());
                    files.put(entry.getKey(), new ScormRepository.StoredFile(key, value.size(), value.mime()));
                }
                return new TransactionTemplate(transactions).execute(status -> {
                    Actor current = access.actor();
                    access.requireManage(current, orgId, current.person().teamId(), current.person().id());
                    long id = repository.insert(orgId, current.person().id(), file.getOriginalFilename(), file.getSize(),
                        new ScormRepository.PackageData(content.title(), content.launch(), files,
                            content.warnings(), content.mastery()));
                    audit.record(current.person().id(), "SCORM_IMPORTED", "scorm_package", Long.toString(id), orgId,
                        Map.of("fileCount", files.size(), "sizeBytes", file.getSize()));
                    return view(repository.require(id), current);
                });
            } catch (IOException | RuntimeException | Error exception) {
                for (String key : written) {
                    try { storage.delete(key); }
                    catch (RuntimeException cleanup) { exception.addSuppressed(cleanup); log.error("SCORM cleanup failed"); }
                }
                throw exception;
            }
        }
    }

    @Transactional(readOnly = true)
    public ScormDtos.PackagePage list(String q, int page, int size) {
        Actor actor = access.actor();
        var scope = access.searchScope(actor);
        boolean ownAllowed = access.canManage(actor, actor.person().orgId(), actor.person().teamId(), actor.person().id());
        Set<Long> visible = access.isLearner(actor) ? identity.activeAncestorIds(actor.person().orgId()) : Set.of();
        var filter = new ScormRepository.Filter(scope.global(), scope.orgIds(), ownAllowed ? actor.person().id() : "",
            actor.person().orgId() == null ? -1L : actor.person().orgId(), visible, q);
        long total = repository.count(filter);
        var lectures = repository.list(filter, page, size);
        var registrations = repository.learnerRegistrations(lectures.stream().map(PackageRow::id).toList(), actor.person().id());
        return new ScormDtos.PackagePage(lectures.stream().map(p -> view(p,
            scope.global() || scope.orgIds().contains(p.orgId()) || ownAllowed && p.uploadedBy().equals(actor.person().id())
                && java.util.Objects.equals(p.orgId(), actor.person().orgId()), registrations.get(p.id()))).toList(),
            page, size, total, (int) ((total + size - 1) / size));
    }

    @Transactional(readOnly = true)
    public ScormDtos.PackageView get(long id) {
        return get(id, null, null);
    }

    @Transactional(readOnly = true)
    public ScormDtos.PackageView get(long id, Long enrollmentId, Long lessonId) {
        Actor actor = access.actor();
        var lecture = repository.require(id);
        var course = context(id, enrollmentId, lessonId, actor);
        if (course == null) requireRead(lecture, actor);
        return course == null ? view(lecture, actor) : view(lecture, false,
            repository.registration(id, actor.person().id(), false, enrollmentId, lessonId, false));
    }

    @Transactional
    public ScormDtos.PackageView publish(long id, long version) {
        Actor actor = access.actor();
        var lecture = repository.require(id);
        if (!canManage(lecture, actor)) throw denied();
        if (!repository.publish(id, version)) throw invalid("expectedVersion", "Bài giảng đã thay đổi. Tải lại trước khi xuất bản.");
        audit.record(actor.person().id(), "SCORM_PUBLISHED", "scorm_package", Long.toString(id), lecture.orgId(), Map.of());
        return view(repository.require(id), actor);
    }

    @Transactional(readOnly = true)
    public ScormDtos.ProgressView progress(long id, boolean preview, Long enrollmentId, Long lessonId) {
        Actor actor = access.actor();
        var lecture = repository.require(id);
        var course = context(id, enrollmentId, lessonId, actor);
        requireLaunchAccess(lecture, actor, preview, course);
        return progress(repository.registration(id, actor.person().id(), preview, enrollmentId, lessonId, false));
    }

    @Transactional
    public ScormDtos.LaunchView launch(long id, boolean preview, Long enrollmentId, Long lessonId) {
        learningLock.acquire();
        Actor actor = access.actor();
        PackageRow lecture = repository.require(id);
        var course = context(id, enrollmentId, lessonId, actor);
        requireLaunchAccess(lecture, actor, preview, course);
        URI player = origin(properties.getPlayerOrigin());
        URI lms = origin(properties.getLmsOrigin());
        if (player.equals(lms)) throw new IllegalStateException("SCORM player must use a separate origin");
        repository.ensureRegistration(id, actor.person().id(), preview, enrollmentId, lessonId);
        var registration = repository.registration(id, actor.person().id(), preview, enrollmentId, lessonId, true);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = Instant.now().plus(properties.getLaunchTtl());
        repository.launch(registration, ScormPackageReader.hash(token), expires,
            SecurityContextUtils.getAuthorizationVersion());
        if (course != null) synchronize(registration, registration.cmi());
        return new ScormDtos.LaunchView(player + "/scorm-player/" + token + "/launch", expires, preview,
            progress(registration));
    }

    @Transactional(readOnly = true)
    public String player(String token) throws IOException {
        Registration registration = requireToken(token, false);
        PackageRow lecture = repository.require(registration.packageId());
        var person = identity.activePerson(registration.userId());
        Map<String, String> cmi = new HashMap<>(registration.cmi());
        cmi.putIfAbsent("cmi.core.lesson_status", "not attempted");
        cmi.put("cmi.core.student_id", person.id());
        cmi.put("cmi.core.student_name", person.name());
        cmi.put("cmi.core.credit", registration.preview() ? "no-credit" : "credit");
        cmi.put("cmi.core.lesson_mode", registration.preview() ? "browse" : "normal");
        cmi.put("cmi.core.entry", "suspend".equals(cmi.get("cmi.core.exit")) ? "resume"
            : registration.cmi().isEmpty() ? "ab-initio" : "");
        cmi.put("cmi.core.total_time", formatTime(registration.totalSeconds().add(registration.sessionSeconds())));
        if (lecture.mastery() != null) cmi.put("cmi.student_data.mastery_score", lecture.mastery().toPlainString());
        String bootstrap = repository.encode(Map.of("cmi", cmi, "launch", "files/" + lecture.launchPath(),
            "origin", properties.getLmsOrigin(), "preview", registration.preview(), "sequence", registration.sequence()));
        String template = new ClassPathResource("scorm/player.html").getContentAsString(StandardCharsets.UTF_8);
        return template.replace("__BOOTSTRAP__", bootstrap.replace("<", "\\u003c"));
    }

    @Transactional(readOnly = true)
    public ObjectDownload file(String token, String path) {
        var r = requireToken(token, false);
        String safe = ScormPackageReader.safePath(path);
        var file = repository.require(r.packageId()).files().get(safe);
        if (file == null) throw invalid("path", "Không tìm thấy tệp trong gói SCORM.");
        return new ObjectDownload(file.key(), file.mime(), safe.substring(safe.lastIndexOf('/') + 1), file.size());
    }

    @Transactional
    public ScormDtos.RuntimeReceipt runtime(String token, ScormDtos.RuntimeRequest request) {
        learningLock.acquire();
        Registration r = requireToken(token, true);
        String payload = repository.encode(request);
        if (payload.getBytes(StandardCharsets.UTF_8).length > 60000) throw invalid("values", "Dữ liệu phiên học quá lớn.");
        String hash = ScormPackageReader.hash(payload);
        if (request.sequence() == r.sequence() && hash.equals(r.payloadHash())) {
            return new ScormDtos.RuntimeReceipt(r.sequence(), progress(r));
        }
        // Each write carries a complete mutable snapshot. An unload flush may overtake an older in-flight write.
        if (request.sequence() <= r.sequence()) throw invalid("sequence", "Phiên học đã thay đổi. Mở lại bài giảng.");
        Map<String, String> values = new HashMap<>(r.cmi());
        for (var entry : request.values().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!mutable(key) || value == null || value.length() > 4096) throw invalid("values", "Trường SCORM không hợp lệ.");
            values.put(key, value);
        }
        String status = values.getOrDefault("cmi.core.lesson_status", "not attempted");
        if (!STATUSES.contains(status)) throw invalid("values", "Trạng thái SCORM không hợp lệ.");
        if (values.getOrDefault("cmi.core.lesson_location", "").length() > 255) throw invalid("values", "Vị trí học quá dài.");
        for (String field : List.of("raw", "min", "max")) number(values.get("cmi.core.score." + field));
        BigDecimal seconds = parseTime(values.getOrDefault("cmi.core.session_time", "0000:00:00"));
        repository.runtime(r, values, seconds.max(r.sessionSeconds()), request.sequence(), hash);
        if (r.enrollmentId() != null) synchronize(r, values);
        return new ScormDtos.RuntimeReceipt(request.sequence(), progress(repository.registration(r.packageId(),
            r.userId(), r.preview(), r.enrollmentId(), r.lessonId(), false)));
    }

    private Registration requireToken(String token, boolean locked) {
        if (!token.matches("[A-Za-z0-9_-]{43}")) throw denied();
        var registration = repository.token(ScormPackageReader.hash(token), locked);
        if (registration == null || registration.expiresAt() == null || !registration.expiresAt().isAfter(Instant.now())) throw denied();
        identity.requireSession(registration.userId(), registration.authVersion());
        Actor actor = identity.actor(registration.userId());
        var lecture = repository.require(registration.packageId());
        var course = context(registration.packageId(), registration.enrollmentId(), registration.lessonId(), actor);
        requireLaunchAccess(lecture, actor, registration.preview(), course);
        return registration;
    }

    private record CourseContext(CourseEnrollment enrollment, CourseLesson lesson) { }

    private void requireLaunchAccess(PackageRow lecture, Actor actor, boolean preview, CourseContext course) {
        if (course != null) {
            if (preview) throw denied();
            return;
        }
        if (preview ? !canManage(lecture, actor) : !canStudy(lecture, actor)) throw denied();
    }

    /** An enrollment is the authority for studying a linked package, including shared courses across units. */
    private CourseContext context(long packageId, Long enrollmentId, Long lessonId, Actor actor) {
        if (enrollmentId == null && lessonId == null) return null;
        if (enrollmentId == null || lessonId == null) {
            throw invalid("enrollmentId", "Cần xác định đủ lần học và bài học SCORM.");
        }
        var enrollment = assignments.requireOwnEnrollment(actor, enrollmentId, true);
        var lesson = learning.require(CourseLesson.class, lessonId);
        var chapter = learning.require(CourseChapter.class, lesson.getChapterId());
        if (!enrollment.getRevisionId().equals(chapter.getRevisionId())
            || !LessonType.SCORM.equals(lesson.getLessonType())
            || !Long.valueOf(packageId).equals(lesson.getScormPackageId())
            || !repository.require(packageId).published()) throw denied();
        return new CourseContext(enrollment, lesson);
    }

    private void synchronize(Registration registration, Map<String, String> values) {
        var course = context(registration.packageId(), registration.enrollmentId(), registration.lessonId(),
            identity.actor(registration.userId()));
        String status = values.getOrDefault("cmi.core.lesson_status", "not attempted");
        courseProgress.synchronizeScorm(registration.userId(), course.enrollment(), course.lesson(),
            "completed".equals(status) || "passed".equals(status));
    }

    private ScormDtos.PackageView view(PackageRow p, Actor actor) {
        return view(p, canManage(p, actor), repository.registration(p.id(), actor.person().id(), false, false));
    }

    private ScormDtos.PackageView view(PackageRow p, boolean canManage, Registration registration) {
        return new ScormDtos.PackageView(p.id(), p.orgId(), p.title(), p.originalName(), p.sizeBytes(), p.fileCount(),
            "1.2", p.published(), canManage, p.version(), p.createdAt(), p.warnings(), progress(registration));
    }

    private void requireRead(PackageRow p, Actor actor) {
        if (!canManage(p, actor) && !canStudy(p, actor)) throw denied();
    }

    private boolean canManage(PackageRow p, Actor actor) {
        return access.canManage(actor, p.orgId(), p.uploadedBy().equals(actor.person().id())
            ? actor.person().teamId() : null, p.uploadedBy());
    }

    private boolean canStudy(PackageRow p, Actor actor) {
        return p.published() && access.isLearner(actor) && identity.activeAncestorIds(actor.person().orgId()).contains(p.orgId());
    }

    private ScormDtos.ProgressView progress(Registration r) {
        if (r == null) return new ScormDtos.ProgressView("not attempted", null, null, null, "", false, null);
        Map<String, String> cmi = r.cmi();
        return new ScormDtos.ProgressView(cmi.getOrDefault("cmi.core.lesson_status", "not attempted"),
            number(cmi.get("cmi.core.score.raw")), number(cmi.get("cmi.core.score.min")),
            number(cmi.get("cmi.core.score.max")), cmi.getOrDefault("cmi.core.lesson_location", ""),
            "suspend".equals(cmi.get("cmi.core.exit")) || !cmi.getOrDefault("cmi.suspend_data", "").isEmpty(), r.updatedAt());
    }

    private static boolean mutable(String key) {
        return key != null && (key.matches("cmi\\.core\\.(lesson_location|lesson_status|exit|session_time|score\\.(raw|min|max))")
            || key.matches("cmi\\.(suspend_data|comments|student_preference\\.(audio|language|speed|text))")
            || key.matches("cmi\\.(interactions|objectives)\\.[0-9]{1,4}\\.[A-Za-z0-9_.]+"));
    }

    private static BigDecimal number(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            BigDecimal number = new BigDecimal(value);
            if (number.abs().compareTo(new BigDecimal("10000")) > 0 || number.scale() > 6) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException exception) { throw invalid("values", "Điểm SCORM không hợp lệ."); }
    }

    private static BigDecimal parseTime(String value) {
        if (!value.matches("[0-9]{2,4}:[0-5][0-9]:[0-5][0-9](\\.[0-9]{1,2})?")) throw invalid("values", "Thời gian SCORM không hợp lệ.");
        String[] parts = value.split(":");
        return new BigDecimal(parts[0]).multiply(new BigDecimal("3600"))
            .add(new BigDecimal(parts[1]).multiply(new BigDecimal("60"))).add(new BigDecimal(parts[2]));
    }

    private static String formatTime(BigDecimal seconds) {
        long total = seconds.longValue();
        return "%04d:%02d:%02d".formatted(total / 3600, total / 60 % 60, total % 60);
    }

    private static URI origin(String value) {
        URI uri = URI.create(value);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
            || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
            || uri.getPath() != null && !uri.getPath().isEmpty()) throw new IllegalStateException("Invalid SCORM origin");
        return uri;
    }

    private static BusinessException invalid(String field, String message) {
        return BusinessException.validation(field, "INVALID_VALUE", message);
    }

    private static AccessDeniedException denied() { return new AccessDeniedException("SCORM scope or launch denied"); }
}
