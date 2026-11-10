package vn.com.coreacademy.knowledge.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class DocumentScopeConverter implements AttributeConverter<DocumentScope, String> {
    @Override
    public String convertToDatabaseColumn(DocumentScope scope) {
        return scope == null ? null : scope.getValue();
    }

    @Override
    public DocumentScope convertToEntityAttribute(String scope) {
        return scope == null ? null : DocumentScope.fromValue(scope);
    }
}
