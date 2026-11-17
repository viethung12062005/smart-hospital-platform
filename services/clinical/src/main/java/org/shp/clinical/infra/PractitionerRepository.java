package org.shp.clinical.infra;

import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.shp.data.mongo.MongoResourceStore;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.data.mongo.MongoResourceStore.ResourceInput;
import org.shp.web.errors.ShpException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Practitioner and PractitionerRole persistence (03 §2.2): writes go through the versioned resource
 * store (resource + history in one transaction); the directory is read directly, keyed on {@code
 * _id} (UUIDv7, so the order is the creation order).
 */
@Component
public class PractitionerRepository {
  public static final String PRACTITIONERS = "practitioners";
  public static final String PRACTITIONER_ROLES = "practitioner_roles";

  private final MongoResourceStore store;
  private final MongoTemplate mongo;

  public PractitionerRepository(MongoResourceStore store, MongoTemplate mongo) {
    this.store = store;
    this.mongo = mongo;
  }

  public Optional<Entry> find(String resourceType, String id) {
    return store.find(resourceType, id);
  }

  /** Version 1 of a new resource. A staff code taken concurrently → 400 (unique index). */
  public Entry create(
      String resourceType, String id, Document resource, Document search, String by) {
    try {
      return store.create(new ResourceInput(resourceType, id, null, resource, search), by, null);
    } catch (DuplicateKeyException e) {
      throw staffCodeInUse();
    }
  }

  /** Compare-and-set on {@code expectedVersion}: stale → 412, missing → 404 (resource store). */
  public Entry update(
      String resourceType,
      String id,
      Document resource,
      Document search,
      int expectedVersion,
      String by) {
    try {
      return store.update(
          new ResourceInput(resourceType, id, null, resource, search), expectedVersion, by, null);
    } catch (DuplicateKeyException e) {
      throw staffCodeInUse();
    }
  }

  /** True when another Practitioner (not {@code exceptId}) already has this staff code. */
  public boolean staffCodeTaken(String staffCode, String exceptId) {
    Criteria criteria = Criteria.where("search.staffCode").is(staffCode);
    if (exceptId != null) {
      criteria = criteria.and("_id").ne(exceptId);
    }
    return mongo.exists(Query.query(criteria), PRACTITIONERS);
  }

  /** Active resources of the directory, oldest first, at most {@code limit}. */
  public List<Entry> listActive(String resourceType, int limit) {
    String collection = "Practitioner".equals(resourceType) ? PRACTITIONERS : PRACTITIONER_ROLES;
    return mongo
        .find(
            Query.query(Criteria.where("search.active").is(true))
                .with(Sort.by(Sort.Direction.ASC, "_id"))
                .limit(limit),
            Document.class,
            collection)
        .stream()
        .map(d ->
            new Entry(
                resourceType,
                d.getString("_id"),
                null,
                d.getInteger("versionId"),
                d.getDate("lastUpdated") == null ? null : d.getDate("lastUpdated").toInstant(),
                d.get("resource", Document.class),
                d.get("search", Document.class)))
        .toList();
  }

  static ShpException staffCodeInUse() {
    return new ShpException(
        "validation-failed", "Practitioner.identifier[staff] is already in use.");
  }
}
