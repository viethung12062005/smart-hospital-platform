package org.shp.web.errors;

/** A standard error code (07-api-conventions §5). */
public record ErrorInfo(
    String code, int status, String fhirIssueType, String messageVi, String messageEn) {}
