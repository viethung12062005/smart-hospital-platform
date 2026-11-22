package org.shp.clinical.config;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.shp.fhir.search.PageTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Signing key of the opaque {@code _page_token} (07 §4). The key comes from the environment ({@code
 * SHP_PAGE_TOKEN_KEY}, at least {@value PageTokens#MIN_KEY_BYTES} bytes). Without it the service
 * generates a random key at startup: no secret is ever in the code, but tokens then stop working
 * after a restart and do not work across replicas, so production must set the variable. This
 * mirrors the scheduling service ({@code PagingConfig}).
 */
@Configuration
class PagingConfig {
  private static final Logger LOG = LoggerFactory.getLogger(PagingConfig.class);

  @Bean
  PageTokens pageTokens(@Value("${shp.paging.token-key:}") String configuredKey) {
    if (configuredKey == null || configuredKey.isBlank()) {
      LOG.warn(
          "shp.paging.token-key (SHP_PAGE_TOKEN_KEY) is not set: using a random per-process"
              + " page-token key; set it for multi-replica deployments");
      byte[] key = new byte[PageTokens.MIN_KEY_BYTES];
      new SecureRandom().nextBytes(key);
      return new PageTokens(key);
    }
    return new PageTokens(configuredKey.getBytes(StandardCharsets.UTF_8));
  }
}
