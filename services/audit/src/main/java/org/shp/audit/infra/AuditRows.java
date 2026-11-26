package org.shp.audit.infra;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Maps an audit record line to the columns of {@code SHPAudit_CL} (05 §1.3). */
final class AuditRows {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AuditRows() {}

  private static String text(JsonNode node, String... path) {
    JsonNode n = node;
    for (String p : path) {
      n = n.path(p);
    }
    return n.isMissingNode() || n.isNull() ? "" : n.asString("");
  }

  /** One row of the custom table; {@code TimeGenerated} is the time the action happened. */
  static Map<String, Object> row(String line) {
    JsonNode r = JSON.readTree(line);
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("TimeGenerated", text(r, "occurredAt"));
    row.put("EventId", text(r, "eventId"));
    row.put("Service", text(r, "service"));
    row.put("Action", text(r, "action"));
    row.put("Outcome", text(r, "outcome"));
    row.put("ActorSubject", text(r, "actor", "subject"));
    row.put("ActorRole", text(r, "actor", "role"));
    row.put("ActorFhirUser", text(r, "actor", "fhirUser"));
    row.put("PatientId", text(r, "patientId"));
    row.put("Resource", text(r, "resource"));
    row.put("PurposeOfUse", text(r, "purposeOfUse"));
    row.put("Reason", text(r, "reason"));
    row.put("TraceId", text(r, "traceId"));
    row.put("ClientIp", text(r, "client", "ip"));
    return row;
  }

  static String toJson(Object value) {
    return JSON.writeValueAsString(value);
  }
}
