package vn.com.coreacademy.community.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import vn.com.coreacademy.common.mapper.CentralMapperConfig;
import vn.com.coreacademy.community.dto.MediaDtos.AssetView;
import vn.com.coreacademy.community.dto.MediaDtos.PartView;
import vn.com.coreacademy.community.entity.AssetUploadPart;
import vn.com.coreacademy.community.entity.CommunityAsset;

@Mapper(config = CentralMapperConfig.class)
public interface CommunityMediaMapper {
    AssetView asset(CommunityAsset asset, Long revisionAssetId);

    @Mapping(target = "status", source = "partStatus")
    PartView part(AssetUploadPart part);
}
