package org.shp.document.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.shp.document.domain.CareLookup;
import org.shp.security.ServiceTokenProvider;
import org.shp.web.auth.ServiceTokenInterceptor;
import org.shp.web.errors.ShpException;

class CareLookupConfigTest {
  private static final ServiceTokenProvider TOKENS =
      new ServiceTokenProvider() {
        @Override
        public String token(String target) {
          return "synthetic-test-token";
        }

        @Override
        public void invalidate(String target) {}
      };

  @Test
  void aClinicalServiceThatNeverAnswersEndsAsDependencyUnavailableWithinTheTimeout()
      throws Exception {
    List<Socket> accepted = new CopyOnWriteArrayList<>();
    Thread acceptor;
    try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      acceptor =
          Thread.ofVirtual()
              .start(() -> {
                try {
                  while (true) {
                    accepted.add(silent.accept()); // accept and never write a byte
                  }
                } catch (Exception closed) {
                  // the server socket was closed at the end of the test
                }
              });
      CareLookup lookup =
          new CareLookupConfig()
              .careLookup(
                  "http://127.0.0.1:" + silent.getLocalPort(),
                  new ServiceTokenInterceptor(TOKENS, "clinical"));

      long start = System.nanoTime();
      assertThatThrownBy(() -> lookup.hasCare("prac-1", "pat-1"))
          .isInstanceOfSatisfying(
              ShpException.class,
              e -> assertThat(e.info().code()).isEqualTo("dependency-unavailable"));
      Duration took = Duration.ofNanos(System.nanoTime() - start);

      assertThat(took)
          .isGreaterThanOrEqualTo(Duration.ofMillis(1500))
          .isLessThan(Duration.ofSeconds(4));
    }
    acceptor.join(Duration.ofSeconds(1));
    for (Socket s : accepted) {
      s.close();
    }
  }
}
