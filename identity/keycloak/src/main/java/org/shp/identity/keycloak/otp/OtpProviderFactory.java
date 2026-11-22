package org.shp.identity.keycloak.otp;

import org.keycloak.provider.ProviderFactory;

/** Factory of an {@link OtpProvider}; the one with the highest {@code order()} is the default. */
public interface OtpProviderFactory extends ProviderFactory<OtpProvider> {}
