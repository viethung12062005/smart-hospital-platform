package org.shp.identity.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * WCAG 2.1 AA contrast (success criterion 1.4.3: 4.5:1 for normal text) of every text/background
 * pair the SHP login theme sets in {@code css/shp.css} (IAM-03). The colours are read from the file
 * that ships, so changing a token without keeping the contrast fails here.
 */
class ThemeContrastTest {
  private static final String CSS = "theme/shp/login/resources/css/shp.css";
  private static final Pattern TOKEN = Pattern.compile("--(shp-[a-z-]+):\\s*(#[0-9a-fA-F]{6});");

  private static Map<String, String> tokens() throws IOException {
    try (InputStream in = ThemeContrastTest.class.getClassLoader().getResourceAsStream(CSS)) {
      assertThat(in).as(CSS + " on the classpath").isNotNull();
      var m = TOKEN.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      Map<String, String> out = new HashMap<>();
      while (m.find()) {
        out.put(m.group(1), m.group(2));
      }
      return out;
    }
  }

  /** Relative luminance, WCAG 2.1 definition. */
  static double luminance(String hex) {
    double[] c = new double[3];
    for (int i = 0; i < 3; i++) {
      double s = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
      c[i] = s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  }

  static double contrast(String a, String b) {
    double la = luminance(a);
    double lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  @Test
  void contrastFormulaMatchesTheWcagExtremes() {
    assertThat(contrast("#000000", "#ffffff"))
        .isCloseTo(21.0, org.assertj.core.data.Offset.offset(0.01));
    assertThat(contrast("#777777", "#777777")).isEqualTo(1.0);
  }

  @ParameterizedTest(name = "{0} on {1}")
  @CsvSource({
    // body text, secondary text and links on the card and on the page background
    "shp-ink, shp-card",
    "shp-ink, shp-bg",
    "shp-muted, shp-card",
    "shp-muted, shp-bg",
    "shp-teal, shp-card",
    "shp-teal-d, shp-card",
    // header (hospital name) on the page background
    "shp-teal-d, shp-bg",
    // primary button text, normal and hover/focus
    "shp-on-teal, shp-teal",
    "shp-on-teal, shp-teal-d",
    // error, success and info alerts on the card
    "shp-err, shp-card",
    "shp-ok, shp-card",
    "shp-info, shp-card"
  })
  void textMeetsWcagAa(String text, String background) throws IOException {
    Map<String, String> t = tokens();
    assertThat(t).containsKeys(text, background);
    assertThat(contrast(t.get(text), t.get(background)))
        .as("%s (%s) on %s (%s)", text, t.get(text), background, t.get(background))
        .isGreaterThanOrEqualTo(4.5);
  }
}
