package org.shp.document.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The blob configuration binds with the design's defaults and honors environment overrides. */
class BlobStorePropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(BlobStorageConfig.class);

  @Test
  void bindsTheDesignedContainerDefaultsAndAnEmptyConnectionString() {
    runner.run(ctx -> {
      var props = ctx.getBean(BlobStoreProperties.class);
      assertThat(props.connectionString()).isEmpty();
      assertThat(props.accountUrl()).isEmpty();
      assertThat(props.configured()).isFalse();
      assertThat(props.createContainers()).isFalse();
      assertThat(props.documentsContainer()).isEqualTo("documents");
      assertThat(props.quarantineContainer()).isEqualTo("quarantine");
      assertThat(props.kbContainer()).isEqualTo("kb");
    });
  }

  @Test
  void environmentOverridesTheConnectionStringAndContainerNames() {
    runner
        .withPropertyValues(
            "shp.blob.connection-string=UseDevelopmentStorage=true",
            "shp.blob.documents-container=docs",
            "shp.blob.quarantine-container=quar",
            "shp.blob.kb-container=knowledge")
        .run(ctx -> {
          var props = ctx.getBean(BlobStoreProperties.class);
          assertThat(props.connectionString()).isEqualTo("UseDevelopmentStorage=true");
          assertThat(props.documentsContainer()).isEqualTo("docs");
          assertThat(props.quarantineContainer()).isEqualTo("quar");
          assertThat(props.kbContainer()).isEqualTo("knowledge");
        });
  }
}
