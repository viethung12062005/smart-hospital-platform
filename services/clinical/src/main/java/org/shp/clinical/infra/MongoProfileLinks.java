package org.shp.clinical.infra;

import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.shp.clinical.app.ProfileLinks;
import org.shp.clinical.domain.LinkedProfile;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Reads {@code profile_links} (03 §2.2). */
@Repository
public class MongoProfileLinks implements ProfileLinks {
  public static final String COLLECTION = "profile_links";

  private final MongoTemplate mongo;

  public MongoProfileLinks(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  @Override
  public List<LinkedProfile> activeFor(String accountSub, Instant now) {
    // status is only a hint: a link past its expiresAt is dead even if no job has marked it yet
    Query q =
        Query.query(
                Criteria.where("accountSub")
                    .is(accountSub)
                    .and("status")
                    .is("active")
                    .andOperator(
                        new Criteria()
                            .orOperator(
                                Criteria.where("expiresAt").is(null),
                                Criteria.where("expiresAt").gt(java.util.Date.from(now)))))
            .with(Sort.by("createdAt", "patientId"));
    return mongo.find(q, Document.class, COLLECTION).stream()
        .map(d ->
            new LinkedProfile(
                d.getString("patientId"),
                d.getString("relationship"),
                d.getString("scope"),
                d.getDate("expiresAt") == null ? null : d.getDate("expiresAt").toInstant()))
        .toList();
  }
}
