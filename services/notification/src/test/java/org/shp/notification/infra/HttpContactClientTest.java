package org.shp.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.notification.app.ContactClient.Contact;
import org.shp.web.errors.ShpException;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/** NTF-08: contact comes from clinical at call time and never reaches a log line. */
class HttpContactClientTest {

  private static final String PHONE = "+84900000001";
  private static final String EMAIL = "synthetic.patient@example.invalid";

  private HttpServer server;
  private volatile int status;
  private volatile String body;
  private volatile String lastPath;
  private ListAppender<ILoggingEvent> logs;
  private Logger clientLogger;
  private HttpContactClient client;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          lastPath = exchange.getRequestURI().getPath();
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    client =
        new HttpContactClient(
            RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build());
    clientLogger = (Logger) LoggerFactory.getLogger(HttpContactClient.class);
    logs = new ListAppender<>();
    logs.start();
    clientLogger.addAppender(logs);
  }

  @AfterEach
  void stop() {
    clientLogger.detachAppender(logs);
    server.stop(0);
  }

  @Test
  void fetchesContactFromTheInternalEndpoint() {
    UUID id = UUID.randomUUID();
    status = 200;
    body = "{\"phone\":\"" + PHONE + "\",\"email\":\"" + EMAIL + "\",\"locale\":\"vi\"}";

    var contact = client.findContact(id);

    assertThat(lastPath).isEqualTo("/internal/v1/patients/" + id + "/contact");
    assertThat(contact).contains(new Contact(PHONE, EMAIL, "vi"));
  }

  @Test
  void toStringIsRedacted() {
    assertThat(new Contact(PHONE, EMAIL, "vi").toString())
        .doesNotContain(PHONE)
        .doesNotContain(EMAIL);
  }

  @Test
  void missingContactIsEmpty() {
    status = 404;
    body = "{}";

    assertThat(client.findContact(UUID.randomUUID())).isEmpty();
  }

  @Test
  void failureIsDependencyUnavailableAndLogsNoContactData() {
    status = 500;
    body = "{\"phone\":\"" + PHONE + "\",\"email\":\"" + EMAIL + "\"}";

    assertThatThrownBy(() -> client.findContact(UUID.randomUUID()))
        .isInstanceOf(ShpException.class);

    assertThat(logs.list).isNotEmpty();
    assertThat(logs.list)
        .allSatisfy(
            e -> assertThat(e.getFormattedMessage()).doesNotContain(PHONE).doesNotContain(EMAIL));
  }
}
