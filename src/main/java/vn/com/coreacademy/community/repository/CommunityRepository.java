package vn.com.coreacademy.community.repository;

import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.entity.CommunityRecord;
import vn.com.coreacademy.community.exception.CommunityResponseCode;

/** Persistence scalar; service giữ khóa ghi chung trước khi kiểm tra grain không có UNIQUE vật lý. */
@Repository
@RequiredArgsConstructor
public class CommunityRepository {
    private final EntityManager entityManager;

    public <T> T require(Class<T> type, Object id) {
        T value = id == null ? null : entityManager.find(type, id);
        if (value == null) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        return value;
    }

    public <T> T find(Class<T> type, Object id) {
        return id == null ? null : entityManager.find(type, id);
    }

    public <T> List<T> list(String hql, Class<T> type, Map<String, ?> parameters) {
        return query(hql, type, parameters).getResultList();
    }

    public <T> List<T> page(String hql, Class<T> type, Map<String, ?> parameters, int offset, int size) {
        return query(hql, type, parameters).setFirstResult(offset).setMaxResults(size).getResultList();
    }

    public <T> T one(String hql, Class<T> type, Map<String, ?> parameters) {
        return query(hql, type, parameters).getSingleResult();
    }

    private <T> TypedQuery<T> query(String hql, Class<T> type, Map<String, ?> parameters) {
        TypedQuery<T> query = entityManager.createQuery(hql, type);
        parameters.forEach(query::setParameter);
        return query;
    }

    public <T extends CommunityRecord> T save(T record, String actorId) {
        if (record.getCreatedBy() == null) {
            record.setCreatedBy(actorId);
        }
        record.setUpdatedBy(actorId);
        if (!entityManager.contains(record)) {
            entityManager.persist(record);
        }
        return record;
    }

    public void remove(CommunityRecord record) {
        entityManager.remove(record);
    }

    public void incrementVersion(CommunityRecord record, String actorId) {
        record.setUpdatedBy(actorId);
        entityManager.lock(record, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }

    public void flush() {
        entityManager.flush();
    }
}
