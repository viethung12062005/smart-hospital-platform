package org.shp.identity.keycloak.otp;

import org.keycloak.provider.Provider;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;

/** Registers the {@code shp-otp} service provider interface with Keycloak. */
public class OtpSpi implements Spi {
  public static final String NAME = "shp-otp";

  @Override
  public boolean isInternal() {
    return false;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public Class<? extends Provider> getProviderClass() {
    return OtpProvider.class;
  }

  @Override
  public Class<? extends ProviderFactory<?>> getProviderFactoryClass() {
    return OtpProviderFactory.class;
  }
}
