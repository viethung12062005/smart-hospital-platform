package org.shp.servicedefaults.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Masks PII/PHI in a log message before it is written (NFR-O4, NFR-SEC13).
 *
 * <p>This is a SECOND line of defense: the primary defense is not logging PII in the first place.
 * Names and addresses cannot be caught by patterns, so those values must be removed where the log
 * record is produced.
 *
 * <p>Patterns mirror {@code shp_platform.pii} (Python), longest/specific first.
 */
public final class PiiMaskingConverter extends MessageConverter {

  /** Common replacement token — does not reveal which kind of value was masked. */
  static final String REDACTED = "[REDACTED]";

  private static final List<Pattern> PATTERNS =
      List.of(
          // email
          Pattern.compile("\\b[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+\\b"),
          // UUID (record id)
          Pattern.compile(
              "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"),
          // insurance card number (BHYT): 2 letters + 13 digits
          Pattern.compile("\\b[A-Z]{2}\\d{13}\\b"),
          // national id: CCCD 12 digits, CMND 9 digits
          Pattern.compile("(?<!\\d)(?:\\d{12}|\\d{9})(?!\\d)"),
          // Vietnamese phone number
          Pattern.compile("(?<![\\w+])(?:\\+?84|0)(?:[\\s.-]?\\d){9}(?!\\d)"),
          // date
          Pattern.compile("\\b(?:\\d{1,2}[/-]\\d{1,2}[/-]\\d{4}|\\d{4}-\\d{2}-\\d{2})\\b"));

  @Override
  public String convert(ILoggingEvent event) {
    String message = event.getFormattedMessage();
    if (message == null) {
      return null;
    }
    for (Pattern pattern : PATTERNS) {
      message = pattern.matcher(message).replaceAll(REDACTED);
    }
    return message;
  }
}
