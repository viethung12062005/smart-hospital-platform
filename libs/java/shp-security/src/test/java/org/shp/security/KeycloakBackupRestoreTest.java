package org.shp.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * IAM-06: a Keycloak database restored from a backup brings Keycloak back with its realm, users and
 * password hashes. Keycloak A runs on PostgreSQL A and gets a user; PostgreSQL A is dumped
 * (pg_dump, custom format) and restored into an empty PostgreSQL B; Keycloak B is started on B
 * <b>without importing any realm</b>, so everything it serves comes from the backup. This tests the
 * logical-backup path locally. Azure's point-in-time restore of the managed server is not covered
 * here.
 */
class KeycloakBackupRestoreTest {
  static final String DB_PASSWORD = "test-only-db-password";
  static final String USER_PASSWORD = "correct-horse-battery-staple";
  static final Network NETWORK = Network.newNetwork();
  static final DockerImageName POSTGRES = DockerImageName.parse("postgres:16-alpine");
  static GenericContainer<?> pgA;
  static GenericContainer<?> pgB;
  static GenericContainer<?> keycloakA;
  static GenericContainer<?> keycloakB;

  static GenericContainer<?> postgres(String alias) {
    return new GenericContainer<>(POSTGRES)
        .withNetwork(NETWORK)
        .withNetworkAliases(alias)
        .withEnv("POSTGRES_DB", "keycloak")
        .withEnv("POSTGRES_USER", "keycloak")
        .withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
        .withExposedPorts(5432)
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
  }

  static GenericContainer<?> keycloak(String pgAlias, boolean importRealm) {
    GenericContainer<?> kc =
        KeycloakTestSupport.container("dev/chart/shp-local-deps/files/realm-shp.json")
            .withNetwork(NETWORK)
            .withEnv("KC_DB", "postgres")
            .withEnv("KC_DB_URL", "jdbc:postgresql://" + pgAlias + ":5432/keycloak")
            .withEnv("KC_DB_USERNAME", "keycloak")
            .withEnv("KC_DB_PASSWORD", DB_PASSWORD);
    kc.setCommand(
        importRealm ? new String[] {"start-dev", "--import-realm"} : new String[] {"start-dev"});
    return kc;
  }

  static String token(GenericContainer<?> kc, String username, String password) throws Exception {
    return JsonPath.read(
        KeycloakTestSupport.form(
            KeycloakTestSupport.base(kc) + "/realms/shp/protocol/openid-connect/token",
            "grant_type=password&client_id=shp-test&username="
                + username
                + "&password="
                + URLEncoder.encode(password, StandardCharsets.UTF_8)),
        "$.access_token");
  }

  @AfterAll
  static void stop() {
    for (GenericContainer<?> c : new GenericContainer<?>[] {keycloakB, keycloakA, pgB, pgA}) {
      if (c != null) {
        c.stop();
      }
    }
    NETWORK.close();
  }

  @Test
  void restoredDatabaseBringsBackRealmUsersAndPasswordHashes() throws Exception {
    pgA = postgres("pg-a");
    pgA.start();
    keycloakA = keycloak("pg-a", true);
    keycloakA.start();
    String baseA = KeycloakTestSupport.base(keycloakA);
    String adminA = KeycloakTestSupport.adminToken(baseA);
    String user =
        """
        {"username":"backup.user","enabled":true,"emailVerified":true,"email":"backup.user@example.test",
         "firstName":"Test","lastName":"User",
         "credentials":[{"type":"password","value":"%s","temporary":false}]}
        """
            .formatted(USER_PASSWORD);
    var created =
        KeycloakTestSupport.admin("POST", baseA + "/admin/realms/shp/users", adminA, user);
    assertThat(created.statusCode()).isEqualTo(201);
    String subA =
        KeycloakTestSupport.claims(token(keycloakA, "backup.user", USER_PASSWORD)).read("$.sub");
    keycloakA.stop(); // a consistent dump needs no writer

    // Backup: logical dump in custom format, then copy it out of the container.
    var dump =
        pgA.execInContainer(
            "pg_dump", "-U", "keycloak", "-Fc", "-f", "/tmp/keycloak.dump", "keycloak");
    assertThat(dump.getExitCode()).as(dump.getStderr()).isZero();
    Path local = Files.createTempFile("keycloak-backup", ".dump");
    pgA.copyFileFromContainer("/tmp/keycloak.dump", local.toString());
    assertThat(Files.size(local)).isGreaterThan(10_000);
    pgA.stop(); // the original database is gone

    // Restore into a brand-new, empty server.
    pgB = postgres("pg-b");
    pgB.start();
    pgB.copyFileToContainer(MountableFile.forHostPath(local), "/tmp/keycloak.dump");
    var restore =
        pgB.execInContainer(
            "pg_restore",
            "-U",
            "keycloak",
            "-d",
            "keycloak",
            "--no-owner",
            "--exit-on-error",
            "/tmp/keycloak.dump");
    assertThat(restore.getExitCode()).as(restore.getStderr()).isZero();
    Files.deleteIfExists(local);

    // Keycloak B starts on the restored data and imports nothing.
    keycloakB = keycloak("pg-b", false);
    keycloakB.withStartupTimeout(Duration.ofMinutes(5));
    keycloakB.start();
    String baseB = KeycloakTestSupport.base(keycloakB);
    // The master realm came back too: the admin password of A still works although B got no
    // bootstrap variables.
    String adminB = KeycloakTestSupport.adminToken(baseB);
    var realm = KeycloakTestSupport.adminGet(baseB + "/admin/realms/shp", adminB);
    assertThat(JsonPath.<Boolean>read(realm, "$.bruteForceProtected")).isTrue();
    assertThat(JsonPath.<String>read(realm, "$.passwordPolicy")).contains("hashAlgorithm(argon2)");
    var clients =
        KeycloakTestSupport.adminGet(baseB + "/admin/realms/shp/clients?clientId=shp-web", adminB);
    assertThat(JsonPath.<java.util.List<?>>read(clients, "$")).hasSize(1);
    // The user and the password hash survived: the same password still signs in, for the same
    // subject.
    var claims = KeycloakTestSupport.claims(token(keycloakB, "backup.user", USER_PASSWORD));
    assertThat(claims.<String>read("$.sub")).isEqualTo(subA);
    assertThat(claims.<java.util.List<String>>read("$.aud")).contains("shp-api");
  }
}
