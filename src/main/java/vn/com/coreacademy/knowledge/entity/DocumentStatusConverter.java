package vn.com.coreacademy.knowledge.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class DocumentStatusConverter implements AttributeConverter<DocumentStatus, String> {
    @Override
    public String convertToDatabaseColumn(DocumentStatus status) {
        return status == null ? null : status.getValue();
    }

    @Override
    public DocumentStatus convertToEntityAttribute(String status) {
        return status == null ? null : DocumentStatus.fromValue(status);
    }
}
