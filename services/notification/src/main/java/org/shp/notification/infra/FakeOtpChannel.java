package org.shp.notification.infra;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.shp.notification.app.OtpChannel;

/**
 * Fake OTP channel for development and tests: it contacts no provider and logs nothing. The last
 * code per destination is kept in memory only so that tests can read it.
 */
public class FakeOtpChannel implements OtpChannel {
  private final Map<String, String> lastCodes = new ConcurrentHashMap<>();
  private volatile boolean failing;

  @Override
  public String name() {
    return "fake";
  }

  @Override
  public boolean handles(String destinationKind) {
    return true;
  }

  @Override
  public void send(String destination, String code, String purpose, String locale)
      throws OtpDeliveryException {
    if (failing) {
      throw new OtpDeliveryException("fake channel is failing");
    }
    lastCodes.put(destination, code);
  }

  /** Test hook: the last code sent to the destination, or null. */
  public String lastCode(String destination) {
    return lastCodes.get(destination);
  }

  /** Test hook: make every delivery fail. */
  public void setFailing(boolean failing) {
    this.failing = failing;
  }
}
