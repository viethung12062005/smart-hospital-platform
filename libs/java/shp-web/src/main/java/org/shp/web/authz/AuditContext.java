package org.shp.web.authz;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.shp.events.audit.AuditActions;
import org.shp.events.audit.AuditRecordBuilder;

/**
 * Lets a handler of an {@link RequireShpAuthz} endpoint add to the audit record of the current
 * request, which {@link AuthzInterceptor} writes after the handler ran: a {@code reason} (for
 * example a break-glass reason, at most {@link AuditRecordBuilder#MAX_REASON_LENGTH} characters)
 * and the real {@code outcome} when it is not the default (for example {@code error} for a handled
 * 409). Both apply only to an allowed decision; a denial keeps its deny reason code.
 */
public final class AuditContext {
  static final String ATTR_REASON = "shp.audit.reason";
  static final String ATTR_OUTCOME = "shp.audit.outcome";
  private static final Set<String> HANDLER_OUTCOMES =
      Set.of(AuditActions.SUCCESS, AuditActions.ERROR);

  private AuditContext() {}

  /** Record this reason on the audit record of the request. */
  public static void reason(HttpServletRequest req, String reason) {
    if (reason != null && reason.length() > AuditRecordBuilder.MAX_REASON_LENGTH) {
      throw new IllegalArgumentException(
          "reason longer than " + AuditRecordBuilder.MAX_REASON_LENGTH + " characters");
    }
    req.setAttribute(ATTR_REASON, reason);
  }

  /** Record {@code success} or {@code error} as the outcome instead of the default. */
  public static void outcome(HttpServletRequest req, String outcome) {
    if (!HANDLER_OUTCOMES.contains(outcome)) {
      throw new IllegalArgumentException("outcome must be success or error: " + outcome);
    }
    req.setAttribute(ATTR_OUTCOME, outcome);
  }

  static String reason(HttpServletRequest req) {
    return req.getAttribute(ATTR_REASON) instanceof String s ? s : null;
  }

  static String outcome(HttpServletRequest req) {
    return req.getAttribute(ATTR_OUTCOME) instanceof String s ? s : null;
  }
}
