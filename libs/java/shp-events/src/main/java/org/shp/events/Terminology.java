package org.shp.events;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Danh mục mã dùng chung (01-domain-model §3), đọc từ contracts/terminology/code-systems.yaml được
 * nhúng vào jar lúc build — cùng một nguồn với web. Tra theo tên value set, vd. {@code
 * Terminology.contains("icd10-subset", "I10")}, vì hai value set LOINC dùng chung một URL.
 */
public final class Terminology {
  private static final String RESOURCE = "shp/terminology/code-systems.yaml";
  private static final Map<String, CodeSystem> SYSTEMS = load();

  private Terminology() {}

  /** Một value set: URL hệ mã FHIR và mã → nhãn hiển thị (giữ thứ tự trong file). */
  public record CodeSystem(String url, Map<String, String> concepts) {}

  /** Tên các value set có trong danh mục. */
  public static Set<String> names() {
    return SYSTEMS.keySet();
  }

  /** true nếu mã thuộc value set; ném {@link IllegalArgumentException} nếu value set không có. */
  public static boolean contains(String valueSet, String code) {
    return code != null && get(valueSet).concepts().containsKey(code);
  }

  /** Nhãn hiển thị của mã, hoặc null nếu mã không thuộc value set. */
  public static String display(String valueSet, String code) {
    return get(valueSet).concepts().get(code);
  }

  /** URL hệ mã (dùng làm {@code Coding.system}). */
  public static String url(String valueSet) {
    return get(valueSet).url();
  }

  /** Toàn bộ mã của value set (chỉ đọc). */
  public static Map<String, String> concepts(String valueSet) {
    return get(valueSet).concepts();
  }

  private static CodeSystem get(String valueSet) {
    CodeSystem cs = SYSTEMS.get(valueSet);
    if (cs == null) {
      throw new IllegalArgumentException(
          "Value set không có trong contracts/terminology: " + valueSet);
    }
    return cs;
  }

  private static Map<String, CodeSystem> load() {
    try (InputStream in = Terminology.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Thiếu resource " + RESOURCE + " trong shp-events");
      }
      Object doc =
          new Yaml(new SafeConstructor(new LoaderOptions()))
              .load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      Map<String, CodeSystem> systems = new LinkedHashMap<>();
      ((Map<?, ?>) ((Map<?, ?>) doc).get("codeSystems"))
          .forEach(
              (name, spec) -> {
                Map<?, ?> s = (Map<?, ?>) spec;
                Map<String, String> concepts = new LinkedHashMap<>();
                // Khóa YAML có thể bị đọc thành số (vd. mã LOINC "8867-4" thì không, nhưng giữ an
                // toàn): luôn đổi sang chuỗi.
                ((Map<?, ?>) s.get("concepts"))
                    .forEach((k, v) -> concepts.put(String.valueOf(k), String.valueOf(v)));
                systems.put(
                    String.valueOf(name),
                    new CodeSystem(
                        String.valueOf(s.get("url")), Collections.unmodifiableMap(concepts)));
              });
      return Collections.unmodifiableMap(systems);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
