package vn.com.coreacademy.identity.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import vn.com.coreacademy.common.mapper.CentralMapperConfig;
import vn.com.coreacademy.identity.dto.UserInfo;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.enums.UserStatus;

@Mapper(config = CentralMapperConfig.class)
public interface UserMapper {
    @Mapping(target = "status", source = "status", qualifiedByName = "userStatusValue")
    UserInfo toInfo(UserAccount user);

    @Named("userStatusValue")
    default String statusValue(UserStatus status) {
        return status == null ? null : status.getValue();
    }
}
