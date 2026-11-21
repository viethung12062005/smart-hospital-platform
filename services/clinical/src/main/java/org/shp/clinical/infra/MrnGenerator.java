package org.shp.clinical.infra;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Medical record numbers {@code BN-YYYY-NNNNNN} (03 §2.5): one atomic {@code $inc} on the {@code
 * mrn_counters} document of the current year, so concurrent callers never receive the same number.
 * The counter is incremented outside the patient transaction on purpose: inside it, two concurrent
 * registrations would conflict on the counter document and abort. A number lost to a failed
 * registration leaves a gap, which is acceptable; a duplicate is not.
 */
@Component
public class MrnGenerator {
  public static final String COLLECTION = "mrn_counters";
  private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private final MongoTemplate mongo;
  private final Clock clock;

  public MrnGenerator(MongoTemplate mongo, Clock clock) {
    this.mongo = mongo;
    this.clock = clock;
  }

  /** The next MRN of the current year (Vietnam calendar). */
  public String next() {
    int year = LocalDate.now(clock.withZone(ZONE)).getYear();
    long seq;
    try {
      seq = increment(year);
    } catch (DuplicateKeyException firstUpsertRace) {
      seq =
          increment(
              year); // two callers created the year's counter at once; the document exists now
    }
    return String.format("BN-%04d-%06d", year, seq);
  }

  private long increment(int year) {
    Document counter =
        mongo.findAndModify(
            Query.query(Criteria.where("_id").is(String.valueOf(year))),
            new Update().inc("seq", 1),
            FindAndModifyOptions.options().upsert(true).returnNew(true),
            Document.class,
            COLLECTION);
    return ((Number) counter.get("seq")).longValue();
  }
}
