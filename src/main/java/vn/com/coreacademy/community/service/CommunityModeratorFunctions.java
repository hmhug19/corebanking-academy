package vn.com.coreacademy.community.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.community.repository.CommunityAccessRepository;
import vn.com.coreacademy.identity.dto.Scope;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.identity.service.FunctionGrantSource;

/**
 * Cấp chức năng menu điều phối cho người điều phối còn hiệu lực của đúng đơn vị hiện tại, kể cả khi họ không có
 * vai trò ADMIN. Quyền xử lý báo cáo vẫn do CommunityPolicy kiểm ở từng API.
 */
@Component
@RequiredArgsConstructor
public class CommunityModeratorFunctions implements FunctionGrantSource {
    private final CommunityAccessRepository access;

    @Override
    public List<FunctionGrant> grants(String userId, Long orgId) {
        if (!access.moderator(userId, orgId)) {
            return List.of();
        }

        return List.of(new FunctionGrant(CommunityCodes.MODERATION_FUNCTION, new Scope(ScopeType.ORG, orgId, null)));
    }
}
