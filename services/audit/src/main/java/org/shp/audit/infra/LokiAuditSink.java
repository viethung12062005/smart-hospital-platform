package org.shp.audit.infra;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Local query copy: pushes records to Loki ({@code POST /loki/api/v1/push}). The stream labels are
 * the low-cardinality columns of {@code SHPAudit_CL} (service, action, outcome, purposeOfUse); the
 * log line is the full row, so the same names are queryable.
 */
public class LokiAuditSink implements AuditSink {

  private final HttpClient http;
  private final URI pushUri;

  public LokiAuditSink(String baseUrl) {
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    this.pushUri = URI.create(baseUrl.replaceAll("/+$", "") + "/loki/api/v1/push");
  }

  @Override
  public void ship(List<String> recordLines) {
    Map<String, Map<String, String>> labelsByKey = new TreeMap<>();
    Map<String, List<List<String>>> valuesByKey = new TreeMap<>();
    for (String line : recordLines) {
      Map<String, Object> row = AuditRows.row(line);
      Map<String, String> labels = new TreeMap<>();
      labels.put("job", "shp-audit");
      labels.put("service", String.valueOf(row.get("Service")));
      labels.put("action", String.valueOf(row.get("Action")));
      labels.put("outcome", String.valueOf(row.get("Outcome")));
      labels.put("purposeOfUse", String.valueOf(row.get("PurposeOfUse")));
      String key = labels.toString();
      labelsByKey.putIfAbsent(key, labels);
      valuesByKey
          .computeIfAbsent(key, k -> new ArrayList<>())
          .add(List.of(nanos(String.valueOf(row.get("TimeGenerated"))), AuditRows.toJson(row)));
    }
    List<Map<String, Object>> streams = new ArrayList<>();
    for (Map.Entry<String, List<List<String>>> e : valuesByKey.entrySet()) {
      streams.add(Map.of("stream", labelsByKey.get(e.getKey()), "values", e.getValue()));
    }
    send(AuditRows.toJson(Map.of("streams", streams)));
  }

  private static String nanos(String timestamp) {
    Instant t = timestamp.isBlank() ? Instant.now() : Instant.parse(timestamp);
    return Long.toString(t.getEpochSecond() * 1_000_000_000L + t.getNano());
  }

  private void send(String json) {
    HttpRequest request =
        HttpRequest.newBuilder(pushUri)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    try {
      HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException("Loki push refused: HTTP " + response.statusCode());
      }
    } catch (IOException e) {
      throw new IllegalStateException("Loki push failed: " + e.getClass().getSimpleName(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Loki push interrupted", e);
    }
  }
}
