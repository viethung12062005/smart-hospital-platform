package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.clinical.infra.PractitionerRepository;
import org.shp.data.mongo.MongoMessaging;
import org.shp.fhir.FhirJson;
import org.shp.web.errors.ShpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * CLN-10 (09 C9, C10; US-006): staff management and the doctor directory against a real MongoDB
 * (Testcontainers), including the unique staff-code index and keyset paging of the directory. Fake
 * data only.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"})
@Testcontainers
class PractitionerServiceTest {
  @Container @ServiceConnection
  static final MongoDBContainer DB =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv(
              "GLIBC_TUNABLES",
              "glibc.pthread.rseq=1") // kernel 6.19-7.0.13: SERVER-121912, see MongoTestBase
          .withReplicaSet();

  static final String PRACTITIONER_ID = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02";
  static final String ROLE_ID = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03";
  static final String ADMIN = "User/admin-sub";

  @Autowired PractitionerService service;
  @Autowired PractitionerRepository repository;
  @Autowired MongoTemplate mongo;

  @BeforeEach
  void emptyTheCollections() {
    for (String c :
        new String[] {
          PractitionerRepository.PRACTITIONERS,
          PractitionerRepository.PRACTITIONER_ROLES,
          MongoMessaging.RESOURCE_HISTORY,
          MongoMessaging.OUTBOX
        }) {
      mongo.getCollection(c).deleteMany(new Document());
    }
  }

  static void assertRejected(Runnable call, String code, String detailStart) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo(code);
              if (detailStart != null) {
                assertThat(e.detail()).startsWith(detailStart);
              }
            });
  }

  static String practitionerWithCode(String staffCode) {
    return ProfileValidatorTest.PRACTITIONER.replace("NV-0042", staffCode);
  }

  /** The same body with the server-assigned id and (optionally) another staff code. */
  static String practitionerBody(String id, String staffCode) {
    return practitionerWithCode(staffCode).replace(PRACTITIONER_ID, id);
  }

  long count(String collection) {
    return mongo.getCollection(collection).countDocuments();
  }

  // ---------- create / update Practitioner (writes) ----------
  @Test
  void createAssignsAnIdAndStoresSearchFields() {
    var s = service.createPractitioner(ProfileValidatorTest.PRACTITIONER, ADMIN);

    String id = s.resource().getIdElement().getIdPart();
    assertThat(id).isNotEqualTo(PRACTITIONER_ID).hasSize(36); // server-assigned UUIDv7
    Document stored = mongo.findById(id, Document.class, PractitionerRepository.PRACTITIONERS);
    assertThat(stored).isNotNull();
    assertThat(stored.get("search", Document.class))
        .containsEntry("staffCode", "NV-0042")
        .containsEntry("active", true)
        .containsEntry("nameTokens", List.of("dr", "le", "minh", "chau", "test"));
    Document history =
        mongo.findById(
            "Practitioner/" + id + "/1", Document.class, MongoMessaging.RESOURCE_HISTORY);
    assertThat(history).isNotNull();
    assertThat(history.getString("changedBy")).isEqualTo(ADMIN);
    assertThat(s.version()).isEqualTo(1);
    assertThat(s.resource().getMeta().getVersionId()).isEqualTo("1");
    // The Admin gets the full stored resource back, including the work email.
    assertThat(s.resource().getTelecom()).hasSize(1);
  }

  @Test
  void clientMetaIsDiscardedBeforeValidation() {
    String withMeta =
        ProfileValidatorTest.PRACTITIONER.replace(
            "\"active\": true,", "\"active\": true, \"meta\": { \"versionId\": \"9\" },");
    assertThat(service.createPractitioner(withMeta, ADMIN).resource().getMeta().getVersionId())
        .isEqualTo("1");
  }

  @Test
  void staffCodeAlreadyInUseIsRejected() {
    service.createPractitioner(ProfileValidatorTest.PRACTITIONER, ADMIN);
    assertRejected(
        () -> service.createPractitioner(ProfileValidatorTest.PRACTITIONER, ADMIN),
        "validation-failed",
        "Practitioner.identifier[staff] is already in use");
    assertThat(count(PractitionerRepository.PRACTITIONERS)).isEqualTo(1);
  }

  @Test
  void outOfProfilePractitionerIsRejectedBeforeAnyWrite() {
    String withBirthDate =
        ProfileValidatorTest.PRACTITIONER.replace(
            "\"active\": true,", "\"active\": true, \"birthDate\": \"1980-01-01\",");
    assertRejected(
        () -> service.createPractitioner(withBirthDate, ADMIN),
        "validation-failed",
        "Practitioner.birthDate");
    assertRejected(
        () -> service.createPractitioner("{\"resourceType\":\"Patient\"}", ADMIN),
        "validation-failed",
        null);
    assertThat(count(PractitionerRepository.PRACTITIONERS)).isZero();
    assertThat(count(MongoMessaging.RESOURCE_HISTORY)).isZero();
  }

  /** TC-SEC-03: fields the profile does not allow never reach the store, on any C10 write. */
  @Test
  void massAssignmentIsRejectedBeforeAnyWrite() {
    String primitiveExtension =
        ProfileValidatorTest.PRACTITIONER.replace(
            "\"active\": true,",
            "\"active\": true, \"_active\": { \"extension\": [ { \"url\":"
                + " \"https://shp.example.org/fhir/StructureDefinition/role\", \"valueString\":"
                + " \"admin\" } ] },");
    assertRejected(
        () -> service.createPractitioner(primitiveExtension, ADMIN),
        "validation-failed",
        "Practitioner.active.extension");

    String rolePeriod =
        ProfileValidatorTest.ROLE.replace(
            "\"active\": true,", "\"active\": true, \"period\": { \"start\": \"2020-01-01\" },");
    assertRejected(
        () -> service.createRole(rolePeriod, ADMIN),
        "validation-failed",
        "PractitionerRole.period");

    assertThat(count(PractitionerRepository.PRACTITIONERS)).isZero();
    assertThat(count(PractitionerRepository.PRACTITIONER_ROLES)).isZero();
    assertThat(count(MongoMessaging.RESOURCE_HISTORY)).isZero();
  }

  @Test
  void staffCodeMustBeNvNnnn() {
    String badCode = ProfileValidatorTest.PRACTITIONER.replace("NV-0042", "NV-42");
    assertRejected(
        () -> service.createPractitioner(badCode, ADMIN),
        "validation-failed",
        "Practitioner.identifier must");
    assertThat(count(PractitionerRepository.PRACTITIONERS)).isZero();
  }

  @Test
  void updateWritesTheNextVersionAndItsHistory() {
    var created = service.createPractitioner(ProfileValidatorTest.PRACTITIONER, ADMIN);
    String id = created.resource().getIdElement().getIdPart();

    String renamed =
        practitionerBody(id, "NV-0042")
            .replace("Dr. Le Minh Chau (test)", "Dr. Le Minh Chau 2 (test)");
    var s = service.updatePractitioner(id, renamed, 1, ADMIN);

    assertThat(s.version()).isEqualTo(2);
    assertThat(s.resource().getMeta().getVersionId()).isEqualTo("2");
    Document stored = mongo.findById(id, Document.class, PractitionerRepository.PRACTITIONERS);
    assertThat(stored.getInteger("versionId")).isEqualTo(2);
    assertThat(stored.get("search", Document.class).getList("nameTokens", String.class))
        .contains("2");
    assertThat(count(MongoMessaging.RESOURCE_HISTORY)).isEqualTo(2); // v1 on create, v2 on update
  }

  @Test
  void updateWithAStaleVersionIsRejected() {
    var created = service.createPractitioner(ProfileValidatorTest.PRACTITIONER, ADMIN);
    String id = created.resource().getIdElement().getIdPart();
    service.updatePractitioner(id, practitionerBody(id, "NV-0042"), 1, ADMIN);

    assertRejected(
        () -> service.updatePractitioner(id, practitionerBody(id, "NV-0042"), 1, ADMIN),
        "version-conflict",
        null);
    assertThat(
            mongo
                .findById(id, Document.class, PractitionerRepository.PRACTITIONERS)
                .getInteger("versionId"))
        .isEqualTo(2);
  }

  @Test
  void updateWithAnotherIdInTheBodyIsRejected() {
    assertRejected(
        () -> service.updatePractitioner("other-id", ProfileValidatorTest.PRACTITIONER, 1, ADMIN),
        "validation-failed",
        "Practitioner.id must match the URL");
  }

  @Test
  void updateToAStaffCodeOfAnotherPractitionerIsRejected() {
    service.createPractitioner(practitionerWithCode("NV-0042"), ADMIN);
    var second = service.createPractitioner(practitionerWithCode("NV-0043"), ADMIN);
    String secondId = second.resource().getIdElement().getIdPart();

    assertRejected(
        () -> service.updatePractitioner(secondId, practitionerBody(secondId, "NV-0042"), 1, ADMIN),
        "validation-failed",
        "Practitioner.identifier[staff] is already in use");
    assertThat(repository.staffCodeTaken("NV-0043", secondId)).isFalse();
    assertThat(repository.staffCodeTaken("NV-0042", secondId)).isTrue();
    assertThat(
            mongo
                .findById(secondId, Document.class, PractitionerRepository.PRACTITIONERS)
                .get("search", Document.class)
                .getString("staffCode"))
        .isEqualTo("NV-0043");
  }

  // ---------- directory reads ----------
  @Test
  void readByIdDropsTelecom() {
    var created = service.createPractitioner(ProfileValidatorTest.PRACTITIONER, ADMIN);
    String id = created.resource().getIdElement().getIdPart();

    var s = service.readPractitioner(id);
    assertThat(s.resource().hasTelecom()).isFalse();
    assertThat(s.resource().getIdentifierFirstRep().getValue()).isEqualTo("NV-0042");
    assertThat(s.version()).isEqualTo(1);
    assertThat(FhirJson.serialize(s.resource())).doesNotContain("chau.le@example.test");
  }

  @Test
  void readOfAnUnknownPractitionerIs404() {
    assertRejected(() -> service.readPractitioner("nope"), "not-found", null);
  }

  @Test
  void directoryListsOnlyActivePractitionersWithoutTelecom() {
    service.createPractitioner(practitionerWithCode("NV-0001"), ADMIN);
    String inactive =
        practitionerWithCode("NV-0002").replace("\"active\": true", "\"active\": false");
    service.createPractitioner(inactive, ADMIN);

    var page = service.directory(20, null);
    assertThat(page.items()).hasSize(1);
    assertThat(page.nextAfterId()).isNull();
    assertThat(page.items().getFirst().hasTelecom()).isFalse();
    assertThat(page.items().getFirst().getMeta().getVersionId()).isEqualTo("1");
  }

  @Test
  void directoryWalksEveryEntryWithKeysetPaging() {
    List<String> ids =
        List.of("NV-0001", "NV-0002", "NV-0003").stream()
            .map(code ->
                service
                    .createPractitioner(practitionerWithCode(code), ADMIN)
                    .resource()
                    .getIdElement()
                    .getIdPart())
            .toList();

    var first = service.directory(2, null);
    assertThat(first.items()).hasSize(2);
    assertThat(first.nextAfterId()).isEqualTo(first.items().getLast().getIdElement().getIdPart());

    var second = service.directory(2, first.nextAfterId());
    assertThat(second.items()).hasSize(1);
    assertThat(second.nextAfterId()).isNull();

    assertThat(
            Stream.concat(first.items().stream(), second.items().stream())
                .map(r -> r.getIdElement().getIdPart())
                .toList())
        .containsExactlyInAnyOrderElementsOf(ids);
  }

  // ---------- PractitionerRole (writes and reads) ----------
  @Test
  void createRoleRequiresAnExistingPractitionerAndStoresSearchFields() {
    var p = service.createPractitioner(ProfileValidatorTest.PRACTITIONER, ADMIN);
    String practitionerId = p.resource().getIdElement().getIdPart();

    var s =
        service.createRole(
            ProfileValidatorTest.ROLE.replace(PRACTITIONER_ID, practitionerId), ADMIN);
    Document stored =
        mongo.findById(
            s.resource().getIdElement().getIdPart(),
            Document.class,
            PractitionerRepository.PRACTITIONER_ROLES);
    assertThat(stored).isNotNull();
    assertThat(stored.get("search", Document.class))
        .containsEntry("practitionerId", practitionerId)
        .containsEntry("specialty", "cardiology")
        .containsEntry("code", "doctor")
        .containsEntry("active", true);
  }

  @Test
  void roleForAnUnknownPractitionerIsRejected() {
    assertRejected(
        () -> service.createRole(ProfileValidatorTest.ROLE, ADMIN),
        "validation-failed",
        "PractitionerRole.practitioner does not exist");
    assertThat(count(PractitionerRepository.PRACTITIONER_ROLES)).isZero();
  }

  @Test
  void roleWithAnUnknownSpecialtyIsRejected() {
    String unknown = ProfileValidatorTest.ROLE.replace("cardiology", "astrology");
    assertRejected(
        () -> service.createRole(unknown, ADMIN),
        "validation-failed",
        "PractitionerRole.specialty.coding.code");
    assertThat(count(PractitionerRepository.PRACTITIONER_ROLES)).isZero();
  }

  @Test
  void updateRoleWritesTheNextVersion() {
    var p = service.createPractitioner(ProfileValidatorTest.PRACTITIONER, ADMIN);
    String practitionerId = p.resource().getIdElement().getIdPart();
    var created =
        service.createRole(
            ProfileValidatorTest.ROLE.replace(PRACTITIONER_ID, practitionerId), ADMIN);
    String roleId = created.resource().getIdElement().getIdPart();

    var s =
        service.updateRole(
            roleId,
            ProfileValidatorTest.ROLE
                .replace(PRACTITIONER_ID, practitionerId)
                .replace(ROLE_ID, roleId),
            1,
            ADMIN);
    assertThat(s.version()).isEqualTo(2);
    assertThat(count(MongoMessaging.RESOURCE_HISTORY))
        .isEqualTo(3); // Practitioner v1 + Role v1, v2
    assertRejected(
        () ->
            service.updateRole(
                "other-id",
                ProfileValidatorTest.ROLE.replace(PRACTITIONER_ID, practitionerId),
                2,
                ADMIN),
        "validation-failed",
        "PractitionerRole.id must match the URL");
  }

  @Test
  void rolesListsActiveRoles() {
    var p = service.createPractitioner(ProfileValidatorTest.PRACTITIONER, ADMIN);
    service.createRole(
        ProfileValidatorTest.ROLE.replace(PRACTITIONER_ID, p.resource().getIdElement().getIdPart()),
        ADMIN);

    var page = service.roles(5, null);
    assertThat(page.items())
        .singleElement()
        .satisfies(r -> assertThat(r.getMeta().getVersionId()).isEqualTo("1"));
    assertThat(page.nextAfterId()).isNull();
  }

  // ---------- name tokens (03 §2.3) ----------
  @Test
  void nameTokensAreLowercaseWithoutDiacritics() {
    assertThat(PractitionerService.nameTokens("ThS.BS. Đặng Thị Ánh Nguyệt"))
        .containsExactly("ths", "bs", "dang", "thi", "anh", "nguyet");
    assertThat(PractitionerService.nameTokens("Lê  Lê")).containsExactly("le");
  }
}
