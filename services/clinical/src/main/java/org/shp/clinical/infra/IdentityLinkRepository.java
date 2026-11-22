package org.shp.clinical.infra;

import java.time.Clock;
import java.util.Date;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * Writes {@code identity_links} (03 §2.2): {@code _id} = OIDC subject, {@code fhirUser}, {@code
 * disabled}. Reading is done by the platform's {@code IdentityLinkClient}.
 */
@Repository
public class IdentityLinkRepository {
  public static final String COLLECTION = "identity_links";

  private final MongoTemplate mongo;
  private final Clock clock;

  public IdentityLinkRepository(MongoTemplate mongo, Clock clock) {
    this.mongo = mongo;
    this.clock = clock;
  }

  public boolean exists(String subject) {
    return mongo.exists(Query.query(Criteria.where("_id").is(subject)), COLLECTION);
  }

  /**
   * Link an account to a Patient. Joins the caller's transaction. A second link for the same
   * subject (or the same Patient) violates a unique key and raises {@code DuplicateKeyException}.
   */
  public void linkPatient(String subject, String issuer, String patientId, String createdBy) {
    mongo.insert(
        new Document("_id", subject)
            .append("issuer", issuer)
            .append("fhirUser", "Patient/" + patientId)
            .append("disabled", false)
            .append("createdAt", Date.from(clock.instant()))
            .append("createdBy", createdBy),
        COLLECTION);
  }
}
