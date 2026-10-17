package vn.com.coreacademy.identity.mapper;

import java.util.List;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import vn.com.coreacademy.common.mapper.CentralMapperConfig;
import vn.com.coreacademy.identity.dto.CreateUserRequest;
import vn.com.coreacademy.identity.dto.UserInfo;
import vn.com.coreacademy.identity.dto.UserSummary;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.enums.UserStatus;
import vn.com.coreacademy.identity.repository.projection.UserSearchRow;
import vn.com.coreacademy.organization.entity.Organization;
import vn.com.coreacademy.organization.entity.Team;

@Mapper(config = CentralMapperConfig.class)
public interface UserMapper {
    // Chỉ map field hồ sơ được phép; password, auth_ver và phân quyền do service xử lý riêng.
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "userId")
    @Mapping(target = "username", source = "username")
    @Mapping(target = "fullName", source = "fullName")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "positionName", source = "positionName")
    @Mapping(target = "orgId", source = "orgId")
    @Mapping(target = "teamId", source = "teamId")
    UserAccount toNewAccount(CreateUserRequest request);

    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "status", source = "user.status", qualifiedByName = "userStatusValue")
    @Mapping(target = "orgId", source = "user.orgId")
    @Mapping(target = "teamId", source = "user.teamId")
    @Mapping(target = "orgCode", source = "organization.code")
    @Mapping(target = "orgName", source = "organization.name")
    @Mapping(target = "orgType", source = "organization.orgType")
    @Mapping(target = "teamCode", source = "team.code")
    @Mapping(target = "teamName", source = "team.name")
    UserInfo toInfo(UserAccount user, Organization organization, Team team);

    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "status", source = "user.status")
    @Mapping(target = "orgId", source = "user.orgId")
    @Mapping(target = "teamId", source = "user.teamId")
    @Mapping(target = "orgName", source = "organization.name")
    @Mapping(target = "orgType", source = "organization.orgType")
    @Mapping(target = "teamName", source = "team.name")
    UserSummary toSummary(UserSearchRow user, Organization organization, Team team,
                          List<String> roleCodes, boolean editable);

    @Named("userStatusValue")
    default String statusValue(UserStatus status) {
        return status == null ? null : status.getValue();
    }
}
