package org.shp.identity.keycloak.testotp;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.shp.identity.keycloak.otp.OtpProvider;
import org.shp.identity.keycloak.otp.OtpProviderFactory;

/** TEST ONLY: registers {@link RecordingOtpProvider}, see there. */
public class RecordingOtpProviderFactory implements OtpProviderFactory {
  private static final RecordingOtpProvider INSTANCE = new RecordingOtpProvider();

  @Override
  public OtpProvider create(KeycloakSession session) {
    return INSTANCE;
  }

  @Override
  public void init(Config.Scope config) {}

  @Override
  public void postInit(KeycloakSessionFactory factory) {}

  @Override
  public void close() {}

  @Override
  public String getId() {
    return "test-recording";
  }

  /** Above any real provider, so the tests that install this JAR always use it. */
  @Override
  public int order() {
    return 1000;
  }
}
