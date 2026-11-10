package org.shp.servicedefaults;

/** Service version information, returned at {@code GET /info}. */
public record ServiceInfo(String name, String version, String gitSha) {}
