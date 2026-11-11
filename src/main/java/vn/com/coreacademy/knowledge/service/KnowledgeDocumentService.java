package vn.com.coreacademy.knowledge.service;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.knowledge.dto.KnowledgeViews.KnowledgeDocumentView;
import vn.com.coreacademy.knowledge.dto.KnowledgeViews.UploaderView;
import vn.com.coreacademy.knowledge.entity.DocumentScope;
import vn.com.coreacademy.knowledge.entity.DocumentStatus;
import vn.com.coreacademy.knowledge.entity.KnowledgeDocument;
import vn.com.coreacademy.knowledge.exception.KnowledgeResponseCode;
import vn.com.coreacademy.knowledge.extraction.FileTypeDetector;
import vn.com.coreacademy.knowledge.extraction.KnowledgeFileType;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository;
import vn.com.coreacademy.knowledge.repository.KnowledgeDocumentRepository;
import vn.com.coreacademy.security.SecurityContextUtils;

/**
 * Upload, list, read and delete knowledge documents. Every signed-in user may upload; who finds a document depends on
 * its scope (personal, unit or global). Processing happens later in {@link DocumentIngestionWorker}, never inside a request.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final String UPLOADED_ACTION = "KNOWLEDGE_DOCUMENT_UPLOADED";
    private static final String DELETED_ACTION = "KNOWLEDGE_DOCUMENT_DELETED";

    private final KnowledgeFileStore store;
    private final FileTypeDetector detector;
    private final KnowledgeDocumentRepository documents;
    private final LearningIdentityService identity;
    private final IdentityAuditService audit;
    private final TransactionTemplate transaction;
    private final KnowledgeAccess access;
    private final DocumentChunkRepository chunks;

    /**
     * Checks the file, stores it and queues it. Refused files never reach the queue; the bytes are written outside the
     * transaction so no database lock is held while the upload is copied, and removed again if anything fails.
     */
    public KnowledgeDocumentView upload(MultipartFile file, DocumentScope requestedScope) {
        String userId = SecurityContextUtils.getUserId();
        Actor actor = identity.actor(userId);
        DocumentScope scope = requestedScope == null ? DocumentScope.UNIT : requestedScope;
        Long scopeOrgId = scopeOrg(actor, scope);
        String name = displayName(file.getOriginalFilename());

        // Step 1: everything refusable without touching the disk.
        KnowledgeFileType type = detector.checkMetadata(name, file.getSize(), file.getContentType());

        // Step 2: store, then confirm the bytes really are that type.
        String key = store.store(file);
        boolean committed = false;
        try {
            detector.checkContent(type, store.resolve(key));

            // Step 3: the row and its audit record commit together.
            KnowledgeDocument saved = transaction.execute(status -> {
                KnowledgeDocument document = new KnowledgeDocument();
                document.setUploadedBy(userId);
                document.setFileName(name);
                document.setMimeType(type.getMimeType());
                document.setScope(scope);
                document.setScopeOrgId(scopeOrgId);
                document.setSizeBytes(file.getSize());
                document.setStorageKey(key);
                KnowledgeDocument created = documents.saveAndFlush(document);
                audit.record(userId, UPLOADED_ACTION, "knowledge_document", created.getId().toString(), null,
                    Map.of("mimeType", created.getMimeType(), "sizeBytes", created.getSizeBytes(), "scope",
                        scope.getValue()));
                return created;
            });
            committed = true;

            return view(saved, identity.fullNames(List.of(userId)));
        } finally {
            if (!committed) {
                store.discard(key);
            }
        }
    }

    /** Newest first. {@code mine} keeps the caller's uploads; {@code statuses} empty or null keeps every status. */
    @Transactional(readOnly = true)
    public PageResponse<KnowledgeDocumentView> list(int page, int size, boolean mine, List<String> statuses) {
        PageRequest paging = PageQuery.of(page, size, "createTime,desc", Set.of("createTime"));
        Set<DocumentStatus> wanted = parseStatuses(statuses);

        String userId = SecurityContextUtils.getUserId();
        Page<KnowledgeDocument> result = mine
            ? documents.findByUploadedByAndStatusIn(userId, wanted, paging)
            : documents.findReadable(wanted, userId, access.visibility(userId).orgIdsOrPlaceholder(), paging);

        // One batch of names for the whole page.
        Map<String, String> names = identity.fullNames(
            result.getContent().stream().map(KnowledgeDocument::getUploadedBy).distinct().toList());

        return PageResponse.from(result.map(document -> view(document, names)));
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentView get(long documentId) {
        KnowledgeDocument document = readable(documentId);

        return view(document, identity.fullNames(List.of(document.getUploadedBy())));
    }

    /**
     * Removes the file, the chunks and the row, so the next question cannot cite the document. A document the caller
     * cannot read answers not-found; one they can read but not delete answers forbidden. A document the worker is
     * processing right now must finish first, otherwise its chunks would be written for a row that no longer exists.
     */
    public void delete(long documentId) {
        String userId = SecurityContextUtils.getUserId();
        Actor actor = identity.actor(userId);
        KnowledgeDocument document = readable(documentId);
        if (!access.mayDelete(actor, document)) {
            throw new AccessDeniedException("The document may be deleted by its owner or an admin in scope");
        }
        if (document.getStatus() == DocumentStatus.PROCESSING) {
            throw BusinessException.validation("documentId", "PROCESSING", "Tài liệu đang được xử lý, hãy thử lại sau.");
        }

        transaction.executeWithoutResult(status -> {
            chunks.deleteByDocument(documentId);
            documents.deleteById(documentId);
            audit.record(userId, DELETED_ACTION, "knowledge_document", String.valueOf(documentId), null,
                Map.of("scope", document.getScope().getValue()));
        });
        store.discard(document.getStorageKey());
    }

    /**
     * Removes a document without a caller: the retention job of course sources runs with nobody signed in. Returns
     * false and leaves it when the worker is processing it right now; the next run tries again.
     */
    public boolean deleteForSystem(long documentId) {
        KnowledgeDocument document = documents.findById(documentId).orElse(null);
        if (document == null) {
            return true;
        }
        if (document.getStatus() == DocumentStatus.PROCESSING) {
            return false;
        }
        transaction.executeWithoutResult(status -> {
            chunks.deleteByDocument(documentId);
            documents.deleteById(documentId);
            audit.record("system", DELETED_ACTION, "knowledge_document", String.valueOf(documentId), null,
                Map.of("scope", document.getScope().getValue()));
        });
        store.discard(document.getStorageKey());

        return true;
    }

    /** A document the caller may read, or not-found: a hidden document must not be told apart from a missing one. */
    private KnowledgeDocument readable(long documentId) {
        String userId = SecurityContextUtils.getUserId();
        KnowledgeDocument document = documents.findById(documentId)
            .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
        boolean visible = switch (document.getScope()) {
            case GLOBAL -> true;
            case PERSONAL -> userId.equals(document.getUploadedBy());
            case UNIT -> access.visibility(userId).orgIds().contains(document.getScopeOrgId());
            case COURSE_SOURCE -> userId.equals(document.getUploadedBy());
        };
        if (!visible) {
            throw new BusinessException(ResponseCode.RESOURCE_NOT_FOUND);
        }

        return document;
    }

    /** The unit a {@code unit} document belongs to is the uploader's own; {@code global} is for admins only. */
    private Long scopeOrg(Actor actor, DocumentScope scope) {
        if (scope == DocumentScope.GLOBAL && !access.mayShareGlobally(actor)) {
            throw new AccessDeniedException("Only admins may share a document with everyone");
        }
        if (scope != DocumentScope.UNIT) {
            return null;
        }
        Long orgId = actor.person().orgId();
        if (orgId == null) {
            throw BusinessException.validation("scope", "NO_UNIT", "Tài khoản chưa thuộc đơn vị nào.");
        }

        return orgId;
    }

    private static Set<DocumentStatus> parseStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return EnumSet.allOf(DocumentStatus.class);
        }
        Set<DocumentStatus> parsed = EnumSet.noneOf(DocumentStatus.class);
        for (String value : statuses) {
            try {
                parsed.add(DocumentStatus.fromValue(value));
            } catch (IllegalArgumentException exception) {
                throw BusinessException.validation("status", "INVALID_VALUE", "Trạng thái không được hỗ trợ.");
            }
        }

        return parsed;
    }

    /** The display name is the last path segment without control characters; it is never used as a path. */
    private static String displayName(String originalName) {
        String name = originalName == null ? "file" : originalName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (name.isBlank() || name.length() > MAX_FILE_NAME_LENGTH) {
            throw new BusinessException(KnowledgeResponseCode.FILE_INVALID);
        }

        return name;
    }

    private static KnowledgeDocumentView view(KnowledgeDocument document, Map<String, String> names) {
        return new KnowledgeDocumentView(document.getId(), document.getFileName(), document.getMimeType(),
            document.getSizeBytes(), document.getScope().getValue(), document.getScopeOrgId(),
            document.getStatus().getValue(), document.getErrorMessage(),
            document.getPageCount(), document.getChunkCount(),
            new UploaderView(document.getUploadedBy(), names.getOrDefault(document.getUploadedBy(), "")),
            document.getCreateTime(), document.getProcessedAt());
    }
}
