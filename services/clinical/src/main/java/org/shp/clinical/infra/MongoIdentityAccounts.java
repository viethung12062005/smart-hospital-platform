package org.shp.clinical.infra;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.shp.clinical.app.IdentityAccounts;
import org.shp.clinical.domain.IdentityAccount;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** {@code identity_links} (03 §2.2): {@code _id} = OIDC subject. */
@Repository
public class MongoIdentityAccounts implements IdentityAccounts {
  public static final String COLLECTION = "identity_links";

  private final MongoTemplate mongo;

  public MongoIdentityAccounts(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  @Override
  public Optional<IdentityAccount> find(String subject) {
    return Optional.ofNullable(mongo.findById(subject, Document.class, COLLECTION))
        .map(MongoIdentityAccounts::toAccount);
  }

  @Override
  public List<IdentityAccount> page(String afterSubject, int limit) {
    Query q = new Query().with(Sort.by("_id")).limit(limit);
    if (afterSubject != null) {
      q.addCriteria(Criteria.where("_id").gt(afterSubject));
    }
    return mongo.find(q, Document.class, COLLECTION).stream()
        .map(MongoIdentityAccounts::toAccount)
        .toList();
  }

  @Override
  public boolean insert(IdentityAccount a, String issuer, String createdBy) {
    var doc =
        new Document("_id", a.subject())
            .append("issuer", issuer)
            .append("fhirUser", a.fhirUser())
            .append("roles", a.roles())
            .append("disabled", a.disabled())
            .append("versionId", a.version())
            .append("createdAt", Date.from(a.createdAt()))
            .append("createdBy", createdBy)
            .append("updatedAt", Date.from(a.updatedAt()))
            .append("updatedBy", createdBy);
    try {
      mongo.insert(doc, COLLECTION);
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  @Override
  public Optional<IdentityAccount> setDisabled(
      String subject, boolean disabled, long expectedVersion, Instant at, String updatedBy) {
    // Rows written before versioning have no versionId and read as version 1.
    Criteria version =
        expectedVersion == 1
            ? new Criteria()
                .orOperator(
                    Criteria.where("versionId").is(1), Criteria.where("versionId").exists(false))
            : Criteria.where("versionId").is(expectedVersion);
    var result =
        mongo.updateFirst(
            Query.query(new Criteria().andOperator(Criteria.where("_id").is(subject), version)),
            new Update()
                .set("versionId", expectedVersion + 1)
                .set("disabled", disabled)
                .set("updatedAt", Date.from(at))
                .set("updatedBy", updatedBy),
            COLLECTION);
    return result.getMatchedCount() == 0 ? Optional.empty() : find(subject);
  }

  @SuppressWarnings("unchecked")
  static IdentityAccount toAccount(Document d) {
    List<String> roles = d.get("roles") instanceof List<?> l ? (List<String>) l : List.of();
    Date created = d.getDate("createdAt");
    Date updated = d.getDate("updatedAt");
    return new IdentityAccount(
        d.getString("_id"),
        d.getString("fhirUser"),
        roles,
        Boolean.TRUE.equals(d.getBoolean("disabled")),
        d.get("versionId") instanceof Number n ? n.longValue() : 1L,
        created == null ? null : created.toInstant(),
        updated == null ? null : updated.toInstant());
  }
}
