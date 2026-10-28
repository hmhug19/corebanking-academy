package vn.com.coreacademy.community.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import vn.com.coreacademy.common.mapper.CentralMapperConfig;
import vn.com.coreacademy.community.dto.ContributionDtos.BadgeView;
import vn.com.coreacademy.community.entity.CommunityBadge;

@Mapper(config = CentralMapperConfig.class)
public interface CommunityBadgeMapper {
    @Mapping(target = "version", source = "rowVersion")
    @Mapping(target = "rule", source = "ruleJson")
    BadgeView badge(CommunityBadge value);
}
