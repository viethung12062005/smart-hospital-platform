package org.shp.clinical.infra;

import java.util.HashSet;
import java.util.Set;
import org.bson.Document;
import org.shp.clinical.app.ProfileDirectory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code patients}, {@code practitioners} and {@code practitioner_roles} (03 §2.2; the
 * administrative fields {@code _id} and {@code search.*}).
 *
 * <p>Depends on CLN-10 (#697) for {@code practitioners} and {@code practitioner_roles}: until that
 * task merges, linking a Practitioner profile finds neither collection and every staff link is
 * refused as an unknown profile. Patient linking does not depend on it.
 */
@Repository
public class MongoProfileDirectory implements ProfileDirectory {
  private final MongoTemplate mongo;

  public MongoProfileDirectory(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  @Override
  public boolean patientExists(String patientId) {
    // a merged-away record (active=false, C8) cannot be linked to a new account
    return exists("patients", patientId);
  }

  @Override
  public boolean practitionerExists(String practitionerId) {
    return exists("practitioners", practitionerId);
  }

  @Override
  public Set<String> activeRoleCodes(String practitionerId) {
    Query q =
        Query.query(
            Criteria.where("search.practitionerId")
                .is(practitionerId)
                .and("search.active")
                .is(true));
    Set<String> codes = new HashSet<>();
    for (Document d : mongo.find(q, Document.class, "practitioner_roles")) {
      Object search = d.get("search");
      if (search instanceof Document s && s.getString("code") != null) {
        codes.add(s.getString("code"));
      }
    }
    return codes;
  }

  private boolean exists(String collection, String id) {
    return mongo.exists(
        Query.query(Criteria.where("_id").is(id).and("search.active").ne(false)), collection);
  }
}
