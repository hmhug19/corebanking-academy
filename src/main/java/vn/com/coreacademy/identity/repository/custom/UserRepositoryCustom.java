package vn.com.coreacademy.identity.repository.custom;

import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import vn.com.coreacademy.identity.enums.UserStatus;
import vn.com.coreacademy.identity.repository.projection.UserSearchRow;

public interface UserRepositoryCustom {
    record UserSearch(String q, String roleCode, Set<Long> selectedOrgIds, Long teamId, UserStatus status,
                      boolean global, Set<Long> visibleOrgIds) {
        public UserSearch {
            selectedOrgIds = selectedOrgIds == null ? null : Set.copyOf(selectedOrgIds);
            visibleOrgIds = Set.copyOf(visibleOrgIds);
        }
    }

    Page<UserSearchRow> searchUsers(UserSearch filter, Pageable pageable);
}
