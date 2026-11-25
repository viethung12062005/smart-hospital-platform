package org.shp.audit.infra;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Cloud query copy: sends rows to the Logs Ingestion API of a Data Collection Rule ({@code POST
 * <endpoint>/dataCollectionRules/<dcr>/streams/<stream>}), table {@code SHPAudit_CL}. The token
 * comes from the pod's managed identity; nothing secret is configured.
 */
public class LogAnalyticsAuditSink implements AuditSink {

  private static final String SCOPE = "https://monitor.azure.com/.default";

  private final HttpClient http;
  private final TokenCredential credential;
  private final URI uri;

  public LogAnalyticsAuditSink(
      String endpoint, String dcrId, String stream, TokenCredential credential) {
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    this.credential = credential;
    this.uri =
        URI.create(
            endpoint.replaceAll("/+$", "")
                + "/dataCollectionRules/"
                + dcrId
                + "/streams/"
                + stream
                + "?api-version=2023-01-01");
  }

  @Override
  public void ship(List<String> recordLines) {
    List<Map<String, Object>> rows = recordLines.stream().map(AuditRows::row).toList();
    AccessToken token = credential.getTokenSync(new TokenRequestContext().addScopes(SCOPE));
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token.getToken())
            .POST(HttpRequest.BodyPublishers.ofString(AuditRows.toJson(rows)))
            .build();
    try {
      HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException("Logs Ingestion refused: HTTP " + response.statusCode());
      }
    } catch (IOException e) {
      throw new IllegalStateException("Logs Ingestion failed: " + e.getClass().getSimpleName(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Logs Ingestion interrupted", e);
    }
  }
}
