package org.shp.clinical.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.bson.Document;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.clinical.app.ProfileValidator;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.web.errors.ShpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/** CLN-03: MRN generation, normalized search fields and uniqueness on a real MongoDB 8. */
@SpringBootTest(
    properties = {"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"})
@Testcontainers
class PatientRepositoryTest {
  @Container @ServiceConnection
  static final MongoDBContainer DB =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1") // see MongoTestBase in shp-data-mongo
          .withReplicaSet();

  private static final Pattern MRN = Pattern.compile("BN-\\d{4}-\\d{6}");

  @Autowired PatientRepository patients;
  @Autowired MrnGenerator mrns;
  @Autowired MongoTemplate mongo;
  @Autowired ProfileValidator validator;

  @BeforeEach
  void clean() {
    mongo.remove(new Query(), "patients");
    mongo.remove(new Query(), "mrn_counters");
    mongo.remove(new Query(), "resource_history");
  }

  static Patient draft(String family, String given, String phone) {
    Patient p = new Patient();
    p.addName()
        .setUse(NameUse.OFFICIAL)
        .setText(family + " " + given)
        .setFamily(family)
        .addGiven(given);
    p.addTelecom().setSystem(ContactPointSystem.PHONE).setValue(phone);
    p.setGender(AdministrativeGender.MALE);
    p.setBirthDateElement(new DateType("1985-03-14"));
    return p;
  }

  @Test
  void createGeneratesMrnIdAndNormalizedSearchFields() {
    Entry e = patients.create(draft("Nguyễn", "Văn An", "+84901234567"), "sub-1", null);

    assertThat(e.versionId()).isEqualTo(1);
    assertThat(e.id()).isEqualTo(e.patientId());
    Document search = patients.find(e.id()).orElseThrow().search();
    assertThat(search.getString("mrn")).matches(MRN.pattern());
    assertThat(search.getList("nameTokens", String.class)).containsExactly("nguyen", "van", "an");
    assertThat(search.getString("phone")).isEqualTo("+84901234567");
    assertThat(search.getString("birthDate")).isEqualTo("1985-03-14");
    assertThat(search.getString("gender")).isEqualTo("male");
    assertThat(search.getBoolean("active")).isTrue();

    Patient stored = PatientRepository.toFhir(patients.find(e.id()).orElseThrow());
    assertThat(stored.getIdentifierFirstRep().getValue()).isEqualTo(search.getString("mrn"));
    assertThat(stored.getMeta().getVersionId()).isEqualTo("1");
    assertThat(mongo.findAll(Document.class, "resource_history")).hasSize(1);
  }

  @Test
  void aCallerSuppliedMrnOrIdIsReplaced() {
    Patient p = draft("Tran", "Binh", "+84901234568");
    p.setId("caller-chosen");
    p.addIdentifier().setSystem("urn:shp:mrn").setValue("BN-1999-000001");
    Entry e = patients.create(p, "sub-1", null);
    assertThat(e.id()).isNotEqualTo("caller-chosen");
    assertThat(e.search().getString("mrn")).isNotEqualTo("BN-1999-000001");
  }

  @Test
  void mrnIsUniqueUnderConcurrentCreation() throws Exception {
    int threads = 24;
    int perThread = 5;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<List<String>>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      int n = t;
      Callable<List<String>> job =
          () -> {
            start.await();
            List<String> out = new ArrayList<>();
            for (int i = 0; i < perThread; i++) {
              out.add(
                  patients
                      .create(
                          draft("Le", "Test" + n + "x" + i, "+849000" + (10000 + n * 10 + i)),
                          "sub",
                          null)
                      .search()
                      .getString("mrn"));
            }
            return out;
          };
      futures.add(pool.submit(job));
    }
    start.countDown();
    List<String> all = new ArrayList<>();
    for (Future<List<String>> f : futures) {
      all.addAll(f.get());
    }
    pool.shutdown();

    assertThat(all)
        .hasSize(threads * perThread)
        .doesNotHaveDuplicates()
        .allMatch(m -> MRN.matcher(m).matches());
    assertThat(Set.copyOf(all)).hasSize(threads * perThread);
    assertThat(mongo.count(new Query(), "patients")).isEqualTo(threads * perThread);
  }

  @Test
  void firstCounterCreationRaceNeverDuplicates() throws Exception {
    int threads = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<String>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      futures.add(
          pool.submit(() -> {
            start.await();
            return mrns.next();
          }));
    }
    start.countDown();
    List<String> all = new ArrayList<>();
    for (Future<String> f : futures) {
      all.add(f.get());
    }
    pool.shutdown();
    assertThat(all).doesNotHaveDuplicates().hasSize(threads);
  }

  @Test
  void theUniqueIndexIsTheBackstopAgainstADuplicateMrn() {
    Entry first = patients.create(draft("Pham", "Minh", "+84901234569"), "sub", null);
    Document copy =
        new Document("_id", "other-id")
            .append("search", new Document("mrn", first.search().getString("mrn")));
    assertThatThrownBy(() -> mongo.insert(copy, "patients"))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void anInvalidProfileWritesNothing() {
    Patient bad = draft("Vo", "Lan", "+84901234570");
    bad.getTelecomFirstRep().setValue("0901234570"); // not E.164: the profile rejects it
    assertThatThrownBy(() -> patients.create(bad, "sub", null)).isInstanceOf(ShpException.class);
    assertThat(mongo.count(new Query(), "patients")).isZero();
    assertThat(mongo.count(new Query(), "resource_history")).isZero();
  }

  @Test
  void updateKeepsVersionsAndRejectsAStaleVersion() {
    Entry e = patients.create(draft("Do", "Hoa", "+84901234571"), "sub", null);
    Patient changed = PatientRepository.toFhir(e);
    changed.getTelecomFirstRep().setValue("+84901234572");
    Entry v2 = patients.update(changed, 1, "sub", null);
    assertThat(v2.versionId()).isEqualTo(2);
    assertThat(v2.search().getString("phone")).isEqualTo("+84901234572");
    assertThatThrownBy(() -> patients.update(changed, 1, "sub", null))
        .isInstanceOfSatisfying(
            ShpException.class, ex -> assertThat(ex.info().code()).isEqualTo("version-conflict"));
  }
}
