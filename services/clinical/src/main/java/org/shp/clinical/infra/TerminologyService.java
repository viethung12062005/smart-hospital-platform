package org.shp.clinical.infra;

import java.util.HashMap;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Read model for the {@code terminology} collection (01 §3): the shared code catalogue is loaded
 * there at startup by {@link TerminologyLoader} and cached in memory, so a code lookup never hits
 * MongoDB (CLN-25 "code lookup < 1 ms"). The cache is refreshed by the loader after every upsert.
 */
@Component
public class TerminologyService {
  public static final String COLLECTION = "terminology";

  private final MongoTemplate mongo;
  private volatile Map<String, Map<String, String>> cache = Map.of();

  public TerminologyService(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  /** True when {@code code} exists in {@code system} (for example {@code loinc-vital-signs}). */
  public boolean isKnown(String system, String code) {
    Map<String, String> concepts = cache.get(system);
    return concepts != null && concepts.containsKey(code);
  }

  /** Reload the in-memory cache from the collection; the loader calls this after an upsert. */
  public void refresh() {
    Map<String, Map<String, String>> fresh = new HashMap<>();
    for (Document doc : mongo.findAll(Document.class, COLLECTION)) {
      Map<String, String> concepts = new HashMap<>();
      for (Document pair : doc.getList("concepts", Document.class)) {
        concepts.put(pair.getString("code"), pair.getString("label"));
      }
      fresh.put(doc.getString("_id"), Map.copyOf(concepts));
    }
    cache = Map.copyOf(fresh);
  }
}
