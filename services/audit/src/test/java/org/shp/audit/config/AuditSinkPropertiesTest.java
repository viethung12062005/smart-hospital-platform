package org.shp.audit.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The Log Analytics sink settings bind with an empty (disabled) default and enable only when both
 * are set.
 */
class AuditSinkPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(AuditSinkConfig.class);

  @Test
  void defaultsToDisabledWhenNothingIsSet() {
    runner.run(ctx -> {
      var props = ctx.getBean(AuditSinkProperties.class);
      assertThat(props.workspaceId()).isEmpty();
      assertThat(props.endpoint()).isEmpty();
      assertThat(props.configured()).isFalse();
    });
  }

  @Test
  void environmentOverridesAreBoundAndBothMustBeSet() {
    runner
        .withPropertyValues(
            "shp.audit-sink.workspace-id=workspace-1",
            "shp.audit-sink.endpoint=https://logs.example",
            "shp.audit-sink.dcr-id=dcr-1")
        .run(ctx -> {
          var props = ctx.getBean(AuditSinkProperties.class);
          assertThat(props.workspaceId()).isEqualTo("workspace-1");
          assertThat(props.endpoint()).isEqualTo("https://logs.example");
          assertThat(props.configured()).isTrue();
        });
  }

  @Test
  void aPartialConfigurationStaysDisabled() {
    assertThat(new AuditSinkProperties("workspace-1", "", "dcr-1", "", "").configured()).isFalse();
    assertThat(
            new AuditSinkProperties("workspace-1", "https://logs.example", "", "", "").configured())
        .isFalse(); // no rule id
    assertThat(new AuditSinkProperties("", "", "", "", "http://loki:3100").lokiConfigured())
        .isTrue();
    assertThat(new AuditSinkProperties("", "https://logs.example", "dcr-1", "", "").configured())
        .isFalse();
  }
}
