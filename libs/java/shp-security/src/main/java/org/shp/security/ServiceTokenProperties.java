package org.shp.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How this service obtains its token for {@code /internal/*} calls ({@code shp.auth.client.*}, env
 * {@code OIDC_TOKEN_ENDPOINT}, {@code OIDC_CLIENT_ID}, {@code OIDC_CLIENT_SECRET}, {@code
 * OIDC_CLIENT_ASSERTION_FILE}).
 *
 * @param tokenEndpoint Keycloak token endpoint; blank derives it from the JWKS address (or the
 *     issuer) of {@link ShpAuthProperties}
 * @param clientId Keycloak client of this service, {@code svc-<service>}; blank means {@code
 *     svc-<spring.application.name>}
 * @param clientSecret client secret (local, fallback); comes from a Secret or Key Vault, never from
 *     git
 * @param assertionFile path of the projected Kubernetes ServiceAccount token sent as a client
 *     assertion (RFC 7523); takes precedence over the secret and is re-read on every request
 *     because the kubelet rotates it in place (IAM-02)
 * @param refreshBefore fetch a new token this long before the current one expires
 * @param timeout connect and read timeout of the token request
 */
@ConfigurationProperties("shp.auth.client")
public record ServiceTokenProperties(
    String tokenEndpoint,
    String clientId,
    String clientSecret,
    String assertionFile,
    Duration refreshBefore,
    Duration timeout) {
  public ServiceTokenProperties {
    tokenEndpoint = tokenEndpoint == null ? "" : tokenEndpoint;
    clientId = clientId == null ? "" : clientId;
    clientSecret = clientSecret == null ? "" : clientSecret;
    assertionFile = assertionFile == null ? "" : assertionFile;
    refreshBefore = refreshBefore == null ? Duration.ofSeconds(30) : refreshBefore;
    timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
  }

  /** The secret must not leak through logging of the configuration. */
  @Override
  public String toString() {
    return "ServiceTokenProperties[tokenEndpoint="
        + tokenEndpoint
        + ", clientId="
        + clientId
        + ", clientSecret="
        + (clientSecret.isEmpty() ? "" : "***")
        + ", assertionFile="
        + assertionFile
        + "]";
  }
}
