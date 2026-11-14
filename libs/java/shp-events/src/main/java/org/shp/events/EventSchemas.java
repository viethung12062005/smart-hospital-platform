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
 * Validate events against the schemas in contracts/events (embedded in the jar). Used in contract
 * tests of producers and consumers.
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
        throw new IllegalStateException("Missing shp/events/envelope.json in shp-events");
      }
      ENVELOPE = envelope;
      DATA = Map.copyOf(data);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private EventSchemas() {}

  /** Types that have a schema (for example shp.scheduling.appointment-booked.v1). */
  public static Set<String> knownTypes() {
    return DATA.keySet();
  }

  /** List of errors; empty if valid. An unknown type is also an error. */
  public static List<String> validate(CloudEvent evt) {
    List<String> errors = new ArrayList<>();
    collect(ENVELOPE, CloudEvents.JSON.valueToTree(evt), "envelope", errors);
    Schema schema = DATA.get(evt.type());
    if (schema == null) {
      errors.add("type has no schema: " + evt.type());
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
