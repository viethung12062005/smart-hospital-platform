package org.shp.web.errors;

/** Một mã lỗi chuẩn (07-api-conventions §5). */
public record ErrorInfo(
    String code, int status, String fhirIssueType, String messageVi, String messageEn) {}
