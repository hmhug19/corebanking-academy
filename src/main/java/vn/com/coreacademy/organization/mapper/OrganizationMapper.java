package vn.com.coreacademy.organization.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import vn.com.coreacademy.common.mapper.CentralMapperConfig;
import vn.com.coreacademy.organization.dto.OrganizationInfo;
import vn.com.coreacademy.organization.dto.TeamInfo;
import vn.com.coreacademy.organization.entity.Organization;
import vn.com.coreacademy.organization.entity.Team;

@Mapper(config = CentralMapperConfig.class)
public interface OrganizationMapper {
    OrganizationInfo toInfo(Organization organization, long memberCount, long teamCount);

    @Mapping(target = "memberPreview", source = "members")
    TeamInfo toInfo(Team team, long memberCount, List<TeamInfo.MemberPreview> members);
}
