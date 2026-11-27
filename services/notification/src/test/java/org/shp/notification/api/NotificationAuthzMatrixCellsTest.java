package org.shp.notification.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.shp.authz.AuthorizationEngine;
import org.shp.authz.AuthzMatrix;
import org.shp.authz.AuthzTestCase;
import org.shp.authz.AuthzTestGenerator;

/**
 * NTF-14 (NFR-SEC2; 15 §3): every cell (role x condition) of the notification resources in {@code
 * contracts/authz/matrix.yaml}, decided by the engine the service runs. The cells are generated
 * from the matrix, so the suite follows it when it changes. No database is needed.
 */
class NotificationAuthzMatrixCellsTest {
  static final AuthzMatrix MATRIX = AuthzMatrix.loadEmbedded();
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T02:00:00Z"), ZoneOffset.UTC);
  static final Set<String> RESOURCES =
      Set.of("Device", "NotificationPreference", "NotificationReminder", "NotificationDeadLetter");

  final AuthorizationEngine engine = new AuthorizationEngine(MATRIX, CLOCK);

  static Stream<AuthzTestCase> notificationCells() {
    return AuthzTestGenerator.embedded().generate().stream()
        .filter(c -> RESOURCES.contains(c.resource()));
  }

  @Test
  void theMatrixDeclaresEveryNotificationResourceAndNoneIsClinicalData() {
    assertThat(MATRIX.actionsByResource().keySet()).containsAll(RESOURCES);
    for (String resource : RESOURCES) {
      assertThat(MATRIX.clinicalByResource().get(resource)).as(resource).isFalse();
    }
    assertThat(notificationCells()).isNotEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("notificationCells")
  void everyCellDecidesAsTheMatrixSays(AuthzTestCase cell) {
    var decision =
        engine.decide(
            AuthzTestGenerator.principal(cell),
            cell.resource(),
            cell.action(),
            AuthzTestGenerator.context(cell, CLOCK.instant()));
    assertThat(decision.allowed()).as(cell.description()).isEqualTo(cell.allowed());
  }

  /** N5-N7 (11 §1): reminder management is for Nurse and Admin only; dead letters for Admin. */
  @Test
  void reminderManagementIsRestrictedToTheRolesTheDesignNames() {
    assertThat(MATRIX.rulesFor("NotificationReminder", "read"))
        .extracting(r -> r.role())
        .containsExactlyInAnyOrder("nurse", "admin");
    assertThat(MATRIX.rulesFor("NotificationDeadLetter", "read"))
        .extracting(r -> r.role())
        .containsExactly("admin");
    assertThat(MATRIX.rulesFor("NotificationDeadLetter", "retry"))
        .extracting(r -> r.role())
        .containsExactly("admin");
  }
}
