package org.shp.identity.keycloak.registration;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.shp.identity.keycloak.otp.Channel;

/**
 * What a patient signs up and signs in with: a Vietnamese mobile number or an email address
 * (ADR-0017). {@link #value()} is the normalised form stored as the username: {@code +84} and nine
 * digits for a phone number, lower case for an email address. Personal data: never logged.
 */
public record Identifier(Channel channel, String value) {
  /** Vietnamese mobile numbers: 0 then 3, 5, 7, 8 or 9 then eight digits (national format). */
  private static final Pattern VN_MOBILE = Pattern.compile("^0[35789][0-9]{8}$");

  private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s.()-]");
  private static final Pattern EMAIL =
      Pattern.compile("^[^@\\s]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$");
  private static final int MAX_EMAIL = 254;

  /**
   * Reads what a person typed: {@code 0901 234 567}, {@code +84 901 234 567}, {@code 84901234567}
   * or an email address. Anything else is empty.
   */
  public static Optional<Identifier> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String s = raw.strip();
    if (s.contains("@")) {
      return s.length() <= MAX_EMAIL && EMAIL.matcher(s).matches()
          ? Optional.of(new Identifier(Channel.EMAIL, s.toLowerCase(Locale.ROOT)))
          : Optional.empty();
    }
    String digits = PHONE_SEPARATORS.matcher(s).replaceAll("");
    if (digits.startsWith("+84")) {
      digits = "0" + digits.substring(3);
    } else if (digits.startsWith("84") && digits.length() == 11) {
      digits = "0" + digits.substring(2);
    }
    return VN_MOBILE.matcher(digits).matches()
        ? Optional.of(new Identifier(Channel.PHONE, "+84" + digits.substring(1)))
        : Optional.empty();
  }

  @Override
  public String toString() {
    return "Identifier[channel=" + channel + "]";
  }
}
