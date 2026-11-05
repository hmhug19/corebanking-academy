package vn.com.coreacademy.identity.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import vn.com.coreacademy.common.mapper.CentralMapperConfig;
import vn.com.coreacademy.identity.dto.AccessRequestView;
import vn.com.coreacademy.identity.entity.AccessRequest;

@Mapper(config = CentralMapperConfig.class)
public interface AccessRequestMapper {
    @Mapping(target = "requestId", source = "request.id")
    @Mapping(target = "kind", source = "request.kind")
    @Mapping(target = "status", source = "request.status")
    @Mapping(target = "orgName", source = "orgName")
    @Mapping(target = "accountFound", source = "request", qualifiedByName = "accountFound")
    @Mapping(target = "createdTime", source = "request.createTime")
    AccessRequestView toView(AccessRequest request, String orgName);

    /** A register request has no account to find; only a reset request that matched one has. */
    @Named("accountFound")
    default boolean accountFound(AccessRequest request) {
        return request.getTargetUserId() != null;
    }
}
