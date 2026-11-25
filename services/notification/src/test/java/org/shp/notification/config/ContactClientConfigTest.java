package org.shp.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.shp.notification.app.ContactClient;
import org.shp.notification.app.ContactClient.Contact;
import org.shp.security.ServiceTokenProvider;
import org.shp.web.auth.ServiceTokenInterceptor;

/** NTF-08: the wired contact client calls clinical with the service's own token. */
class ContactClientConfigTest {

  @Test
  void wiredClientSendsTheServiceTokenToClinical() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body =
              "{\"phone\":\"+84900000001\",\"email\":\"a@example.invalid\",\"locale\":\"vi\"}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      ServiceTokenProvider tokens =
          new ServiceTokenProvider() {
            @Override
            public String token(String target) {
              return "synthetic-token-for-" + target;
            }

            @Override
            public void invalidate(String target) {}
          };
      ContactClient client =
          new ContactClientConfig()
              .contactClient(
                  "http://127.0.0.1:" + server.getAddress().getPort(),
                  new ServiceTokenInterceptor(tokens, "clinical"));

      var contact = client.findContact(UUID.randomUUID());

      assertThat(contact).contains(new Contact("+84900000001", "a@example.invalid", "vi"));
      assertThat(authorization.get()).isEqualTo("Bearer synthetic-token-for-clinical");
    } finally {
      server.stop(0);
    }
  }
}
