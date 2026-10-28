package org.shp.servicedefaults;

/** Thông tin phiên bản của service, trả ở {@code GET /info}. */
public record ServiceInfo(String name, String version, String gitSha) {}
