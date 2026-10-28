package org.shp.events.audit;

import java.util.Set;

/** Giá trị hợp lệ của {@code action} và {@code outcome} (05-storage-audit-blob §1.1). */
public final class AuditActions {
  public static final String READ = "read";
  public static final String SEARCH = "search";
  public static final String CREATE = "create";
  public static final String UPDATE = "update";
  public static final String DELETE = "delete";
  public static final String DOWNLOAD_URL = "download-url";
  public static final String AI_QUERY = "ai-query";
  public static final String AI_SUMMARY = "ai-summary";
  public static final String BREAK_GLASS_START = "break-glass-start";
  public static final String EXPORT = "export";
  public static final String LOGIN = "login";

  public static final Set<String> ALL =
      Set.of(
          READ,
          SEARCH,
          CREATE,
          UPDATE,
          DELETE,
          DOWNLOAD_URL,
          AI_QUERY,
          AI_SUMMARY,
          BREAK_GLASS_START,
          EXPORT,
          LOGIN);

  public static final String SUCCESS = "success";
  public static final String DENIED = "denied";
  public static final String ERROR = "error";
  public static final Set<String> OUTCOMES = Set.of(SUCCESS, DENIED, ERROR);

  private AuditActions() {}
}
