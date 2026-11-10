package org.shp.events.audit;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;
import org.shp.events.Uuids;

/**
 * Build audit records by the rules: mask IPs, shorten the user-agent, reject sensitive values in
 * resource and reason.
 */
public final class AuditRecordBuilder {
  public static final int MAX_REASON_LENGTH = 200;
  public static final int MAX_USER_AGENT_LENGTH = 120;
  private static final java.util.regex.Pattern ON_BEHALF_OF =
      java.util.regex.Pattern.compile("^(Patient|Practitioner)/[A-Za-z0-9-]+$");

  private static final Pattern RESOURCE =
      Pattern.compile("^[A-Za-z]+(/[A-Za-z0-9-]{1,64})?(\\?[A-Za-z_:.,-]+(&[A-Za-z_:.,-]+)*)?$");
  private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

  private AuditRecordBuilder() {}

  /**
   * Optional parameters may be null: patientId, purposeOfUse, reason, traceId, clientIp, userAgent.
   * No delegation: onBehalfOf/relationship = null.
   */
  public static AuditRecord build(
      Clock clock,
      String service,
      String action,
      String outcome,
      AuditRecord.Actor actor,
      String resource,
      UUID patientId,
      String purposeOfUse,
      String reason,
      String traceId,
      String clientIp,
      String userAgent) {
    return build(
        clock,
        service,
        action,
        outcome,
        actor,
        null,
        null,
        resource,
        patientId,
        purposeOfUse,
        reason,
        traceId,
        clientIp,
        userAgent);
  }

  /**
   * Acting on behalf of a managed person (ADR-0018): {@code onBehalfOf} is the record actually
   * accessed, {@code actor} is still the signed-in account; {@code relationship} = guardian/proxy.
   */
  public static AuditRecord build(
      Clock clock,
      String service,
      String action,
      String outcome,
      AuditRecord.Actor actor,
      String onBehalfOf,
      String relationship,
      String resource,
      UUID patientId,
      String purposeOfUse,
      String reason,
      String traceId,
      String clientIp,
      String userAgent) {
    if (!AuditActions.ALL.contains(action)) {
      throw new IllegalArgumentException("invalid action: " + action);
    }
    if (!AuditActions.OUTCOMES.contains(outcome)) {
      throw new IllegalArgumentException("invalid outcome: " + outcome);
    }
    if (resource == null || !RESOURCE.matcher(resource).matches()) {
      // Allow only "Type", "Type/id" or "Type?param-name&..." — never a search value
      // (which may be PII).
      throw new IllegalArgumentException(
          "resource may contain only the type, id or search PARAMETER NAMES (no values)");
    }
    if (onBehalfOf != null && !ON_BEHALF_OF.matcher(onBehalfOf).matches()) {
      throw new IllegalArgumentException("onBehalfOf must be Patient/<id> or Practitioner/<id>");
    }
    if (relationship != null
        && (onBehalfOf == null
            || !(relationship.equals("guardian") || relationship.equals("proxy")))) {
      throw new IllegalArgumentException(
          "relationship may only be guardian/proxy and requires onBehalfOf");
    }
    if (reason != null && reason.length() > MAX_REASON_LENGTH) {
      throw new IllegalArgumentException("reason longer than " + MAX_REASON_LENGTH + " characters");
    }
    String ip = maskIp(clientIp);
    String ua =
        userAgent == null || userAgent.isEmpty()
            ? null
            : userAgent.length() > MAX_USER_AGENT_LENGTH
                ? userAgent.substring(0, MAX_USER_AGENT_LENGTH)
                : userAgent;
    return new AuditRecord(
        Uuids.v7(clock),
        Instant.now(clock).truncatedTo(ChronoUnit.MILLIS),
        service,
        action,
        outcome,
        actor,
        onBehalfOf,
        relationship,
        patientId,
        resource,
        purposeOfUse,
        reason,
        traceId,
        ip == null && ua == null ? null : new AuditRecord.Client(ip, ua));
  }

  /**
   * IPv4: mask the last octet ({@code 10.1.2.x}). IPv6: keep the first 3 groups ({@code
   * 2001:0db8:0001::x}). Not an IP → null. Only parses numeric IP strings, never does a DNS lookup.
   */
  public static String maskIp(String ip) {
    if (ip == null || ip.isEmpty() || !(IPV4.matcher(ip).matches() || ip.contains(":"))) {
      return null;
    }
    InetAddress address;
    try {
      address = InetAddress.getByName(ip); // numeric string/IPv6 literal: no DNS lookup
    } catch (UnknownHostException | SecurityException e) {
      return null;
    }
    byte[] b = address.getAddress();
    if (address instanceof Inet4Address) {
      return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".x";
    }
    if (address instanceof Inet6Address) {
      return "%02x%02x:%02x%02x:%02x%02x::x".formatted(b[0], b[1], b[2], b[3], b[4], b[5]);
    }
    return null;
  }
}
