package org.shp.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.shp.web.authz.UnprotectedEndpoints;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Test khung: service khởi động với DB thật, endpoint nền tảng đúng, KHÔNG có endpoint nào thiếu
 * khai báo quyền.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"})
@Testcontainers
class NotificationApplicationTest {
  @Container @ServiceConnection
  static final MongoDBContainer DB =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withReplicaSet();

  @Autowired Environment env;
  @Autowired ApplicationContext ctx;

  private HttpResponse<String> get(String path) throws Exception {
    var uri = URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
    try (var client = HttpClient.newHttpClient()) {
      return client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  @Test
  void platformEndpointsWork() throws Exception {
    assertThat(get("/health/live").statusCode()).isEqualTo(200);
    assertThat(get("/health/ready").statusCode()).isEqualTo(200);
    assertThat(get("/info").body()).contains("\"name\":\"notification\"");
  }

  @Test
  void everyEndpointDeclaresItsAuthorizationRule() {
    assertThat(UnprotectedEndpoints.find(ctx)).isEmpty();
  }

  @Test
  void apiRequiresAToken() throws Exception {
    var r = get("/api/v1/anything");
    assertThat(r.statusCode()).isEqualTo(401);
    assertThat(r.body()).contains("unauthenticated");
  }
}
