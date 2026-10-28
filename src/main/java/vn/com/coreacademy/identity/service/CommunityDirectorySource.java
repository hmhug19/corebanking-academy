package vn.com.coreacademy.identity.service;

import org.springframework.stereotype.Component;

/** Projection công khai cho query contributor; không cung cấp credential, email hoặc role Identity. */
@Component
public class CommunityDirectorySource {
    public String activePeopleSql() {
        return """
            select user_id, full_name, org_id from learning_hub.users
            where status = 'active' and org_id = :visibleOrg
            """;
    }
}
