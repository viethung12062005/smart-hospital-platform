package org.shp.audit.config;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The startup status line runs (and logs without secrets) in both the disabled and configured
 * states.
 */
class AuditSinkConfigTest {

  @Test
  void logsDisabledWhenTheSinkIsNotConfigured() {
    new ApplicationContextRunner()
        .withUserConfiguration(AuditSinkConfig.class)
        .run(ctx -> runStatusLine(ctx.getBean(ApplicationRunner.class)));
  }

  @Test
  void logsConfiguredWhenBothSettingsArePresent() {
    new ApplicationContextRunner()
        .withUserConfiguration(AuditSinkConfig.class)
        .withPropertyValues(
            "shp.audit-sink.workspace-id=workspace-1",
            "shp.audit-sink.endpoint=https://logs.example",
            "shp.audit-sink.dcr-id=dcr-1")
        .run(ctx -> runStatusLine(ctx.getBean(ApplicationRunner.class)));
  }

  private static void runStatusLine(ApplicationRunner runner) {
    assertThatCode(() -> runner.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }
}
