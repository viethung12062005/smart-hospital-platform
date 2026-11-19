package org.shp.clinical.infra;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.shp.clinical.app.CareRelationships;
import org.shp.clinical.domain.CareRelationship;
import org.shp.clinical.domain.CareRelationship.Source;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * {@code care_relationships} (03 §2.2): {@code _id = <practitionerId>|<patientId>}, {@code
 * sources[] {type, id, status, at, version}}, {@code validUntil} ({@code null} = no care). The care
 * check (CLN-12) is one read by {@code _id} with {@code validUntil > now}.
 */
@Repository
public class MongoCareRelationships implements CareRelationships {
  public static final String COLLECTION = MongoClinicalReads.CARE_RELATIONSHIPS;

  private final MongoTemplate mongo;

  public MongoCareRelationships(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  @Override
  public Optional<CareRelationship> find(String practitionerId, String patientId) {
    return Optional.ofNullable(
            mongo.findById(
                MongoClinicalReads.careId(practitionerId, patientId), Document.class, COLLECTION))
        .map(MongoCareRelationships::fromDocument);
  }

  @Override
  public List<CareRelationship> findBySource(String patientId, String type, String sourceId) {
    Query q =
        Query.query(
            Criteria.where("patientId")
                .is(patientId)
                .and("sources")
                .elemMatch(Criteria.where("type").is(type).and("id").is(sourceId)));
    return mongo.find(q, Document.class, COLLECTION).stream()
        .map(MongoCareRelationships::fromDocument)
        .toList();
  }

  @Override
  public void save(CareRelationship relationship) {
    mongo.save(toDocument(relationship), COLLECTION);
  }

  static Document toDocument(CareRelationship r) {
    List<Document> sources =
        r.sources().stream()
            .map(s ->
                new Document("type", s.type())
                    .append("id", s.id())
                    .append("status", s.status())
                    .append("at", Date.from(s.at()))
                    .append("version", s.version()))
            .toList();
    return new Document("_id", r.id())
        .append("practitionerId", r.practitionerId())
        .append("patientId", r.patientId())
        .append("sources", sources)
        .append("validUntil", r.validUntil().map(Date::from).orElse(null));
  }

  static CareRelationship fromDocument(Document d) {
    List<Source> sources =
        d.getList("sources", Document.class, List.of()).stream()
            .map(s ->
                new Source(
                    s.getString("type"),
                    s.getString("id"),
                    s.getString("status"),
                    s.getDate("at").toInstant(),
                    ((Number) s.getOrDefault("version", 0L)).longValue()))
            .toList();
    return new CareRelationship(d.getString("practitionerId"), d.getString("patientId"), sources);
  }
}
