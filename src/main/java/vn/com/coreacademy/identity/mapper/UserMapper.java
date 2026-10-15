package vn.com.coreacademy.identity.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import vn.com.coreacademy.common.mapper.CentralMapperConfig;
import vn.com.coreacademy.identity.dto.UserInfo;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.enums.UserStatus;
import vn.com.coreacademy.organization.entity.Organization;
import vn.com.coreacademy.organization.entity.Team;

@Mapper(config = CentralMapperConfig.class)
public interface UserMapper {
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

    @Named("userStatusValue")
    default String statusValue(UserStatus status) {
        return status == null ? null : status.getValue();
    }
}
