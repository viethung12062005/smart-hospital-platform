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
 * Dựng bản ghi audit đúng quy tắc: che IP, rút gọn user-agent, chặn giá trị nhạy cảm trong resource
 * và reason.
 */
public final class AuditRecordBuilder {
  public static final int MAX_REASON_LENGTH = 200;
  public static final int MAX_USER_AGENT_LENGTH = 120;

  private static final Pattern RESOURCE =
      Pattern.compile("^[A-Za-z]+(/[A-Za-z0-9-]{1,64})?(\\?[A-Za-z_:.,-]+(&[A-Za-z_:.,-]+)*)?$");
  private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

  private AuditRecordBuilder() {}

  /**
   * Tham số tùy chọn có thể null: patientId, purposeOfUse, reason, traceId, clientIp, userAgent.
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
    if (!AuditActions.ALL.contains(action)) {
      throw new IllegalArgumentException("action không hợp lệ: " + action);
    }
    if (!AuditActions.OUTCOMES.contains(outcome)) {
      throw new IllegalArgumentException("outcome không hợp lệ: " + outcome);
    }
    if (resource == null || !RESOURCE.matcher(resource).matches()) {
      // Chỉ cho "Loại", "Loại/id" hoặc "Loại?tên-tham-số&..." — không bao giờ có giá trị tìm kiếm
      // (có thể là PII).
      throw new IllegalArgumentException(
          "resource chỉ được chứa loại, id hoặc TÊN tham số tìm kiếm (không có giá trị)");
    }
    if (reason != null && reason.length() > MAX_REASON_LENGTH) {
      throw new IllegalArgumentException("reason dài quá " + MAX_REASON_LENGTH + " ký tự");
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
        patientId,
        resource,
        purposeOfUse,
        reason,
        traceId,
        ip == null && ua == null ? null : new AuditRecord.Client(ip, ua));
  }

  /**
   * IPv4: che octet cuối ({@code 10.1.2.x}). IPv6: giữ 3 nhóm đầu ({@code 2001:0db8:0001::x}).
   * Không phải IP → null. Chỉ phân tích chuỗi IP dạng số, không bao giờ tra DNS.
   */
  public static String maskIp(String ip) {
    if (ip == null || ip.isEmpty() || !(IPV4.matcher(ip).matches() || ip.contains(":"))) {
      return null;
    }
    InetAddress address;
    try {
      address = InetAddress.getByName(ip); // chuỗi số/IPv6 literal: không tra DNS
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
