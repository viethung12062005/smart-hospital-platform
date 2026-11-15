package org.shp.clinical.infra;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the shared code catalogue into the {@code terminology} collection at startup (CLN-25).
 * Every {@code contracts/terminology/dist/*.json} file (copied into the jar as {@code
 * terminology/*.json} by the module's resource configuration) becomes one document: {@code {_id:
 * <system>, url, concepts: [{code, label}], loadedAt}}. Upserting by {@code _id} makes a restart
 * idempotent — no duplicate documents.
 *
 * <p>Concepts are stored as an array of {@code {code, label}} pairs rather than a map because some
 * codes (ICD-10, for example {@code A15.0}) contain dots, which MongoDB forbids in field names.
 */
@Component
public class TerminologyLoader implements ApplicationRunner {
  private static final String PATTERN = "classpath*:terminology/*.json";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final MongoTemplate mongo;
  private final Clock clock;
  private final TerminologyService terminology;
  private final ResourcePatternResolver resources;

  public TerminologyLoader(
      MongoTemplate mongo,
      Clock clock,
      TerminologyService terminology,
      ApplicationContext context) {
    this.mongo = mongo;
    this.clock = clock;
    this.terminology = terminology;
    this.resources = context;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    for (Resource file : resources.getResources(PATTERN)) {
      String system = systemName(file);
      JsonNode root = JSON.readTree(file.getInputStream());
      List<Document> concepts = new ArrayList<>();
      root.get("concepts")
          .properties()
          .forEach(e ->
              concepts.add(
                  new Document("code", e.getKey()).append("label", e.getValue().asString())));
      Document doc =
          new Document()
              .append("_id", system)
              .append("url", root.get("url").asString())
              .append("concepts", concepts)
              .append("loadedAt", Date.from(clock.instant()));
      mongo.save(doc, TerminologyService.COLLECTION);
    }
    terminology.refresh();
  }

  private static String systemName(Resource file) {
    String name = file.getFilename();
    if (name == null || !name.endsWith(".json")) {
      throw new IllegalStateException("Unexpected terminology resource: " + file);
    }
    return name.substring(0, name.length() - ".json".length());
  }
}
