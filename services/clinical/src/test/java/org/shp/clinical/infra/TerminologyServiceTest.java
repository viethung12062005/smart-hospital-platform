package org.shp.clinical.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * CLN-25 done-when "code lookup < 1 ms": after the startup load, a lookup reads the in-memory table
 * only and never MongoDB. Unit test, no database.
 */
class TerminologyServiceTest {

  static Document system(String id, String... codes) {
    return new Document("_id", id)
        .append(
            "concepts",
            java.util.Arrays.stream(codes)
                .map(c -> new Document("code", c).append("label", "label " + c))
                .toList());
  }

  final MongoTemplate mongo = mock(MongoTemplate.class);
  final TerminologyService service = new TerminologyService(mongo);

  void load() {
    when(mongo.findAll(Document.class, TerminologyService.COLLECTION))
        .thenReturn(
            List.of(
                system("icd10-subset", "I10", "E11.9", "J06.9"),
                system("loinc-vital-signs", "8310-5", "8867-4")));
    service.refresh();
  }

  @Test
  void beforeTheLoadNothingIsKnown() {
    assertThat(service.isKnown("icd10-subset", "I10")).isFalse();
  }

  @Test
  void lookupsUseTheLoadedTables() {
    load();
    assertThat(service.isKnown("icd10-subset", "I10")).isTrue();
    assertThat(service.isKnown("icd10-subset", "8310-5")).isFalse(); // right code, wrong system
    assertThat(service.isKnown("loinc-vital-signs", "8310-5")).isTrue();
    assertThat(service.isKnown("unknown-system", "I10")).isFalse();
  }

  @Test
  void aLookupNeverReadsMongoDb() {
    load();
    for (int i = 0; i < 1_000; i++) {
      service.isKnown("icd10-subset", "I10");
    }
    verify(mongo, times(1)).findAll(Document.class, TerminologyService.COLLECTION);
  }

  @Test
  void aLookupTakesLessThanOneMillisecond() {
    load();
    for (int i = 0; i < 10_000; i++) { // warm up the JIT
      service.isKnown("icd10-subset", i % 2 == 0 ? "I10" : "Z99.9");
    }
    int n = 100_000;
    long start = System.nanoTime();
    for (int i = 0; i < n; i++) {
      service.isKnown("icd10-subset", i % 2 == 0 ? "I10" : "Z99.9");
    }
    double averageMillis = (System.nanoTime() - start) / 1e6 / n;
    assertThat(averageMillis).isLessThan(1.0);
  }

  @Test
  void refreshReplacesTheTablesAtomically() {
    load();
    when(mongo.findAll(Document.class, TerminologyService.COLLECTION))
        .thenReturn(List.of(system("icd10-subset", "K21.9")));
    service.refresh();
    assertThat(service.isKnown("icd10-subset", "K21.9")).isTrue();
    assertThat(service.isKnown("icd10-subset", "I10")).isFalse();
    assertThat(service.isKnown("loinc-vital-signs", "8310-5")).isFalse();
  }
}
