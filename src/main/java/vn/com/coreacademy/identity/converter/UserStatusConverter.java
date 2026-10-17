package vn.com.coreacademy.identity.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import vn.com.coreacademy.identity.enums.UserStatus;

@Converter
public class UserStatusConverter implements AttributeConverter<UserStatus, String> {
    @Override
    public String convertToDatabaseColumn(UserStatus status) {
        return status == null ? null : status.getValue();
    }

    @Override
    public UserStatus convertToEntityAttribute(String status) {
        return status == null ? null : UserStatus.fromValue(status);
    }
}
