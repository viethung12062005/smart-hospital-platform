package org.shp.notification.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.shp.notification.domain.ReminderKind;

/**
 * Loads and renders version-controlled notification templates.
 *
 * <p>Template resources follow {@code templates/<kind>.<locale>.txt}.
 */
public class NotificationTemplateRenderer {

  private static final Set<String> SUPPORTED_LOCALES = Set.of("vi", "en");

  /**
   * Template text by resource path. Templates are immutable classpath resources, so they are read
   * once.
   */
  private final Map<String, String> templates = new ConcurrentHashMap<>();

  private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)}");

  /**
   * Renders a template using the supplied variables.
   *
   * @param kind reminder kind
   * @param locale supported locale: vi or en
   * @param variables template variables such as doctorName, startTime and place
   * @return rendered notification content
   */
  public String render(ReminderKind kind, String locale, Map<String, String> variables) {

    Objects.requireNonNull(kind, "Reminder kind is required");
    Objects.requireNonNull(variables, "Template variables are required");

    if (locale == null || !SUPPORTED_LOCALES.contains(locale)) {
      throw new IllegalArgumentException("Locale must be either vi or en");
    }

    String template = loadTemplate(kind, locale);
    Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
    StringBuilder rendered = new StringBuilder();

    while (matcher.find()) {
      String variableName = matcher.group(1);
      String value = variables.get(variableName);

      if (value == null || value.isBlank()) {
        throw new NotificationTemplateException(
            "Missing required template variable: " + variableName);
      }

      matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
    }

    matcher.appendTail(rendered);
    return rendered.toString();
  }

  private String loadTemplate(ReminderKind kind, String locale) {
    String resourcePath = "templates/" + kind.value() + "." + locale + ".txt";
    String cached = templates.get(resourcePath);
    if (cached != null) {
      return cached;
    }
    // A missing or unreadable template throws and is not cached, so every call reports it.
    String text = readTemplate(resourcePath);
    templates.put(resourcePath, text);
    return text;
  }

  private String readTemplate(String resourcePath) {
    ClassLoader classLoader = getClass().getClassLoader();

    try (InputStream input = classLoader.getResourceAsStream(resourcePath)) {
      if (input == null) {
        throw new NotificationTemplateException("Notification template not found: " + resourcePath);
      }

      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new NotificationTemplateException(
          "Could not read notification template: " + resourcePath, exception);
    }
  }
}
