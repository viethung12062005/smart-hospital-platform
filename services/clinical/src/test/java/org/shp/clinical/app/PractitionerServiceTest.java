package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.shp.clinical.infra.PractitionerRepository;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.fhir.FhirJson;
import org.shp.web.errors.ShpException;

/** CLN-10 (09 C9, C10; US-006): staff management and the doctor directory. Fake data only. */
class PractitionerServiceTest {
  static final String PRACTITIONER_ID = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02";
  static final String ADMIN = "User/admin-sub";

  final PractitionerRepository repository = mock(PractitionerRepository.class);
  final PractitionerService service =
      new PractitionerService(
          repository,
          new ProfileValidator(ProfileValidatorTest.terminology(), ProfileValidatorTest.CLOCK),
          ProfileValidatorTest.CLOCK);

  /** The repository returns what it was given, at {@code version}. */
  void storeReturnsInput(int version) {
    when(repository.create(anyString(), anyString(), any(), any(), anyString()))
        .thenAnswer(a ->
            entry(a.getArgument(0), a.getArgument(1), a.getArgument(2), a.getArgument(3), version));
    when(repository.update(anyString(), anyString(), any(), any(), anyInt(), anyString()))
        .thenAnswer(a ->
            entry(a.getArgument(0), a.getArgument(1), a.getArgument(2), a.getArgument(3), version));
  }

  static Entry entry(String type, String id, Document resource, Document search, int version) {
    return new Entry(
        type, id, null, version, Instant.parse("2026-11-17T03:00:00Z"), resource, search);
  }

  static Entry stored(String json, int version) {
    var doc = Document.parse(json);
    return entry(doc.getString("resourceType"), doc.getString("id"), doc, new Document(), version);
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

  // ---------- create / update Practitioner ----------
  @Test
  void createAssignsAnIdAndStoresSearchFields() {
    storeReturnsInput(1);
    var s = service.createPractitioner(ProfileValidatorTest.PRACTITIONER, ADMIN);

    ArgumentCaptor<Document> search = ArgumentCaptor.forClass(Document.class);
    ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
    verify(repository).create(eq("Practitioner"), id.capture(), any(), search.capture(), eq(ADMIN));
    assertThat(id.getValue()).isNotEqualTo(PRACTITIONER_ID).hasSize(36); // server-assigned UUIDv7
    assertThat(search.getValue())
        .containsEntry("staffCode", "NV-0042")
        .containsEntry("active", true)
        .containsEntry("nameTokens", List.of("dr", "le", "minh", "chau", "test"));
    assertThat(s.version()).isEqualTo(1);
    assertThat(s.resource().getIdElement().getIdPart()).isEqualTo(id.getValue());
    assertThat(s.resource().getMeta().getVersionId()).isEqualTo("1");
    // The Admin gets the full stored resource back, including the work email.
    assertThat(s.resource().getTelecom()).hasSize(1);
  }

  @Test
  void clientMetaIsDiscardedBeforeValidation() {
    storeReturnsInput(1);
    String withMeta =
        ProfileValidatorTest.PRACTITIONER.replace(
            "\"active\": true,", "\"active\": true, \"meta\": { \"versionId\": \"9\" },");
    assertThat(service.createPractitioner(withMeta, ADMIN).resource().getMeta().getVersionId())
        .isEqualTo("1");
  }

  @Test
  void staffCodeAlreadyInUseIsRejected() {
    when(repository.staffCodeTaken("NV-0042", null)).thenReturn(true);
    assertRejected(
        () -> service.createPractitioner(ProfileValidatorTest.PRACTITIONER, ADMIN),
        "validation-failed",
        "Practitioner.identifier[staff] is already in use");
    verify(repository, never()).create(anyString(), anyString(), any(), any(), anyString());
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
    verify(repository, never()).create(anyString(), anyString(), any(), any(), anyString());
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
    assertRejected(
        () -> service.updatePractitioner(PRACTITIONER_ID, primitiveExtension, 1, ADMIN),
        "validation-failed",
        "Practitioner.active.extension");

    String rolePeriod =
        ProfileValidatorTest.ROLE.replace(
            "\"active\": true,", "\"active\": true, \"period\": { \"start\": \"2020-01-01\" },");
    assertRejected(
        () -> service.createRole(rolePeriod, ADMIN),
        "validation-failed",
        "PractitionerRole.period");
    assertRejected(
        () -> service.updateRole("0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03", rolePeriod, 1, ADMIN),
        "validation-failed",
        "PractitionerRole.period");

    verify(repository, never()).create(anyString(), anyString(), any(), any(), anyString());
    verify(repository, never())
        .update(anyString(), anyString(), any(), any(), anyInt(), anyString());
  }

  @Test
  void staffCodeMustBeNvNnnn() {
    String badCode = ProfileValidatorTest.PRACTITIONER.replace("NV-0042", "NV-42");
    assertRejected(
        () -> service.createPractitioner(badCode, ADMIN),
        "validation-failed",
        "Practitioner.identifier must");
  }

  @Test
  void updateUsesTheExpectedVersionAndChecksTheStaffCodeAgainstOthers() {
    storeReturnsInput(4);
    var s =
        service.updatePractitioner(PRACTITIONER_ID, ProfileValidatorTest.PRACTITIONER, 3, ADMIN);
    verify(repository).staffCodeTaken("NV-0042", PRACTITIONER_ID);
    verify(repository)
        .update(eq("Practitioner"), eq(PRACTITIONER_ID), any(), any(), eq(3), eq(ADMIN));
    assertThat(s.version()).isEqualTo(4);
    assertThat(s.resource().getMeta().getVersionId()).isEqualTo("4");
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
    when(repository.staffCodeTaken("NV-0042", PRACTITIONER_ID)).thenReturn(true);
    assertRejected(
        () ->
            service.updatePractitioner(
                PRACTITIONER_ID, ProfileValidatorTest.PRACTITIONER, 1, ADMIN),
        "validation-failed",
        "Practitioner.identifier[staff] is already in use");
  }

  // ---------- directory never exposes contact details ----------
  @Test
  void readByIdDropsTelecom() {
    when(repository.find("Practitioner", PRACTITIONER_ID))
        .thenReturn(Optional.of(stored(ProfileValidatorTest.PRACTITIONER, 2)));
    var s = service.readPractitioner(PRACTITIONER_ID);
    assertThat(s.resource().hasTelecom()).isFalse();
    assertThat(s.resource().getIdentifierFirstRep().getValue()).isEqualTo("NV-0042");
    assertThat(s.version()).isEqualTo(2);
    assertThat(FhirJson.serialize(s.resource())).doesNotContain("chau.le@example.test");
  }

  @Test
  void readOfAnUnknownPractitionerIs404() {
    when(repository.find("Practitioner", "nope")).thenReturn(Optional.empty());
    assertRejected(() -> service.readPractitioner("nope"), "not-found", null);
  }

  @Test
  void directoryListsActivePractitionersWithoutTelecom() {
    when(repository.listActive("Practitioner", 20))
        .thenReturn(List.of(stored(ProfileValidatorTest.PRACTITIONER, 1)));
    List<Practitioner> page = service.directory(20);
    assertThat(page).hasSize(1);
    assertThat(page.getFirst().hasTelecom()).isFalse();
    assertThat(page.getFirst().getMeta().getVersionId()).isEqualTo("1");
  }

  // ---------- PractitionerRole ----------
  @Test
  void createRoleRequiresAnExistingPractitionerAndStoresSearchFields() {
    storeReturnsInput(1);
    when(repository.find("Practitioner", PRACTITIONER_ID))
        .thenReturn(Optional.of(stored(ProfileValidatorTest.PRACTITIONER, 1)));
    service.createRole(ProfileValidatorTest.ROLE, ADMIN);

    ArgumentCaptor<Document> search = ArgumentCaptor.forClass(Document.class);
    verify(repository)
        .create(eq("PractitionerRole"), anyString(), any(), search.capture(), eq(ADMIN));
    assertThat(search.getValue())
        .containsEntry("practitionerId", PRACTITIONER_ID)
        .containsEntry("specialty", "cardiology")
        .containsEntry("code", "doctor")
        .containsEntry("active", true);
  }

  @Test
  void roleForAnUnknownPractitionerIsRejected() {
    when(repository.find("Practitioner", PRACTITIONER_ID)).thenReturn(Optional.empty());
    assertRejected(
        () -> service.createRole(ProfileValidatorTest.ROLE, ADMIN),
        "validation-failed",
        "PractitionerRole.practitioner does not exist");
    verify(repository, never()).create(anyString(), anyString(), any(), any(), anyString());
  }

  @Test
  void roleWithAnUnknownSpecialtyIsRejected() {
    String unknown = ProfileValidatorTest.ROLE.replace("cardiology", "astrology");
    assertRejected(
        () -> service.createRole(unknown, ADMIN),
        "validation-failed",
        "PractitionerRole.specialty.coding.code");
  }

  @Test
  void updateRoleUsesTheExpectedVersion() {
    storeReturnsInput(2);
    when(repository.find("Practitioner", PRACTITIONER_ID))
        .thenReturn(Optional.of(stored(ProfileValidatorTest.PRACTITIONER, 1)));
    String roleId = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03";
    var s = service.updateRole(roleId, ProfileValidatorTest.ROLE, 1, ADMIN);
    verify(repository).update(eq("PractitionerRole"), eq(roleId), any(), any(), eq(1), eq(ADMIN));
    assertThat(s.version()).isEqualTo(2);
    assertRejected(
        () -> service.updateRole("other-id", ProfileValidatorTest.ROLE, 1, ADMIN),
        "validation-failed",
        "PractitionerRole.id must match the URL");
  }

  @Test
  void rolesListsActiveRoles() {
    when(repository.listActive("PractitionerRole", 5))
        .thenReturn(List.of(stored(ProfileValidatorTest.ROLE, 3)));
    List<PractitionerRole> page = service.roles(5);
    assertThat(page)
        .singleElement()
        .satisfies(r -> assertThat(r.getMeta().getVersionId()).isEqualTo("3"));
  }

  // ---------- name tokens (03 §2.3) ----------
  @Test
  void nameTokensAreLowercaseWithoutDiacritics() {
    assertThat(PractitionerService.nameTokens("ThS.BS. Đặng Thị Ánh Nguyệt"))
        .containsExactly("ths", "bs", "dang", "thi", "anh", "nguyet");
    assertThat(PractitionerService.nameTokens("Lê  Lê")).containsExactly("le");
  }

  @Test
  void createChecksTheStaffCodeAgainstEveryPractitioner() {
    // On create there is no "self" to exclude from the uniqueness check.
    storeReturnsInput(1);
    service.createPractitioner(ProfileValidatorTest.PRACTITIONER, ADMIN);
    verify(repository).staffCodeTaken(eq("NV-0042"), isNull());
  }
}
