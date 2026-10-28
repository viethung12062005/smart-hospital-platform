package org.shp.clinical.config;

import java.util.Optional;
import org.bson.Document;
import org.shp.web.auth.IdentityLink;
import org.shp.web.auth.IdentityLinkClient;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Đọc {@code identity_links} (03 §2): {@code _id} = OIDC subject, {@code fhirUser}, {@code
 * disabled}.
 */
final class MongoIdentityLinkClient implements IdentityLinkClient {
  private final MongoTemplate mongo;

  MongoIdentityLinkClient(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  @Override
  public Optional<IdentityLink> find(String subject) {
    Document d = mongo.findById(subject, Document.class, "identity_links");
    if (d == null) {
      return Optional.empty();
    }
    String[] parts = d.getString("fhirUser").split("/", 2);
    return Optional.of(
        new IdentityLink(parts[0], parts[1], Boolean.TRUE.equals(d.getBoolean("disabled"))));
  }
}
