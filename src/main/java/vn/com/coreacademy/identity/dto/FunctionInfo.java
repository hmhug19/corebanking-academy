package vn.com.coreacademy.identity.dto;

public record FunctionInfo(Long id, String code, String name, String routePattern, Long parentFunctionId, int sortNo) {
}
