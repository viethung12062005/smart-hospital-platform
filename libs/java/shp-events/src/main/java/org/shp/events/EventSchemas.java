package org.shp.events;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;

/**
 * Kiểm event theo schema trong contracts/events (nhúng vào jar). Dùng trong test hợp đồng của
 * producer và consumer.
 */
public final class EventSchemas {
  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final Schema ENVELOPE;
  private static final Map<String, Schema> DATA;

  static {
    try {
      var resolver = new PathMatchingResourcePatternResolver(EventSchemas.class.getClassLoader());
      Schema envelope = null;
      Map<String, Schema> data = new TreeMap<>();
      for (Resource r : resolver.getResources("classpath*:shp/events/**/*.json")) {
        String url = r.getURL().toString().replace('\\', '/');
        String rel = url.substring(url.indexOf("shp/events/") + "shp/events/".length());
        Schema schema =
            REGISTRY.getSchema(
                new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        if (rel.equals("envelope.json")) {
          envelope = schema;
        } else {
          // <context>/<event>.v1.json → shp.<context>.<event>.v1
          String[] parts = rel.replace(".json", "").split("/");
          data.put("shp." + parts[0] + "." + parts[1], schema);
        }
      }
      if (envelope == null) {
        throw new IllegalStateException("Thiếu shp/events/envelope.json trong shp-events");
      }
      ENVELOPE = envelope;
      DATA = Map.copyOf(data);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private EventSchemas() {}

  /** Các type có schema (ví dụ shp.scheduling.appointment-booked.v1). */
  public static Set<String> knownTypes() {
    return DATA.keySet();
  }

  /** Danh sách lỗi; rỗng nếu hợp lệ. Type lạ cũng là lỗi. */
  public static List<String> validate(CloudEvent evt) {
    List<String> errors = new ArrayList<>();
    collect(ENVELOPE, CloudEvents.JSON.valueToTree(evt), "envelope", errors);
    Schema schema = DATA.get(evt.type());
    if (schema == null) {
      errors.add("type chưa có schema: " + evt.type());
      return errors;
    }
    collect(schema, evt.data(), "data", errors);
    return errors;
  }

  private static void collect(Schema schema, JsonNode node, String where, List<String> errors) {
    schema
        .validate(node, ctx -> ctx.executionConfig(c -> c.formatAssertionsEnabled(true)))
        .forEach(e -> errors.add(where + " " + e.getInstanceLocation() + ": " + e.getMessage()));
  }
}
