#!/usr/bin/env python3
"""Sinh khung (walking skeleton) cho service Java/Spring Boot (ADR-0015): pom, Application, cấu hình nền tảng, test, Dockerfile,
values local, workflow CI. Dev chỉ thêm logic nghiệp vụ.

Dùng: python3 -I tools/scaffold/java_service.py [<name> ...]     (không tham số = cả 5 service)
Không ghi đè file đã tồn tại (dev đã sửa thì giữ nguyên). Gateway viết tay (WebFlux), không sinh bằng script này.
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEMPLATES_REPO = "viethung12062005/shp-platform-templates"
TEMPLATES_REF = "v0.1.2"

# name -> (owner, mô tả, db: "postgres" | "mongo")
SERVICES = {
    "scheduling": ("D1", "Lịch bác sĩ, slot, đặt/hủy lịch hẹn (FHIR Schedule/Slot/Appointment)", "postgres"),
    "notification": ("D1", "Nhắc lịch, gửi thông báo qua kênh, chống trùng", "mongo"),
    "clinical": ("D2", "Hồ sơ bệnh nhân, encounter, tìm kiếm lịch sử, liên kết tài khoản (FHIR)", "mongo"),
    "document": ("D2", "Tài liệu lâm sàng: link upload/tải có hạn, trạng thái quét", "mongo"),
    "audit": ("D2", "Nhận và tra cứu audit, chuỗi hash bất biến", "mongo"),
}


def cls(name: str) -> str:
    return name[:1].upper() + name[1:]


def pom(name: str, desc: str, db: str) -> str:
    if db == "postgres":
        data = """    <dependency>
      <groupId>org.shp</groupId>
      <artifactId>shp-data-postgres</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-flyway</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>"""
        tc = "testcontainers-postgresql"
    else:
        data = """    <dependency>
      <groupId>org.shp</groupId>
      <artifactId>shp-data-mongo</artifactId>
    </dependency>"""
        tc = "testcontainers-mongodb"
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.shp</groupId>
    <artifactId>shp-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath>../../pom.xml</relativePath>
  </parent>
  <artifactId>{name}</artifactId>
  <name>{name} — {desc}</name>

  <dependencies>
    <dependency>
      <groupId>org.shp</groupId>
      <artifactId>shp-service-defaults</artifactId>
    </dependency>
    <dependency>
      <groupId>org.shp</groupId>
      <artifactId>shp-web</artifactId>
    </dependency>
{data}
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>{tc}</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers-junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>


  <build>
    <finalName>app</finalName>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
"""


def platform_config(name: str, db: str) -> str:
    pkg = f"org.shp.{name}"
    identity = (
        f"""  /** clinical sở hữu {{@code identity_links}} nên tra thẳng DB của mình (03 §2). */
  @Bean
  IdentityLinkClient identityLinkClient(MongoTemplate mongo) {{
    return new MongoIdentityLinkClient(mongo);
  }}
"""
        if name == "clinical"
        else """  /** Tra identity link qua API nội bộ của clinical (09 §4). Token workload identity: task IAC/SHR (chưa có). */
  @Bean
  IdentityLinkClient identityLinkClient(@Value("${shp.clinical.url}") String clinicalUrl) {
    return new HttpIdentityLinkClient(RestClient.builder().baseUrl(clinicalUrl).build());
  }
"""
    )
    if db == "postgres":
        data_imports = """import org.shp.data.postgres.PostgresIdempotency;
import org.shp.data.postgres.PostgresInbox;
import org.shp.data.postgres.PostgresOutbox;
import org.shp.data.postgres.PostgresOutboxAuditSink;
import org.springframework.jdbc.core.simple.JdbcClient;
"""
        data_beans = """  @Bean
  PostgresOutbox outbox(JdbcClient jdbc) {
    return new PostgresOutbox(jdbc);
  }

  @Bean
  PostgresInbox inbox(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
    return new PostgresInbox(jdbc, tx, clock);
  }

  @Bean
  PostgresIdempotency idempotency(JdbcClient jdbc, Clock clock) {
    return new PostgresIdempotency(jdbc, clock);
  }

  /** Audit ghi bền vào outbox (transaction riêng) — phát lên bus bởi relay. */
  @Bean
  AuditSink auditSink(PostgresOutbox outbox, TransactionTemplate tx, Clock clock) {
    return new PostgresOutboxAuditSink(outbox, tx, clock);
  }
"""
    else:
        data_imports = """import org.shp.data.mongo.MongoIdempotency;
import org.shp.data.mongo.MongoInbox;
import org.shp.data.mongo.MongoMessaging;
import org.shp.data.mongo.MongoOutbox;
import org.shp.data.mongo.MongoOutboxAuditSink;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
"""
        data_beans = """  /** Transaction MongoDB (cần replica set) — Boot không tự tạo. */
  @Bean
  MongoTransactionManager transactionManager(MongoDatabaseFactory factory) {
    return new MongoTransactionManager(factory);
  }

  @Bean
  MongoOutbox outbox(MongoTemplate mongo) {
    return new MongoOutbox(mongo);
  }

  @Bean
  MongoInbox inbox(MongoTemplate mongo, TransactionTemplate tx, Clock clock) {
    return new MongoInbox(mongo, tx, clock);
  }

  @Bean
  MongoIdempotency idempotency(MongoTemplate mongo, Clock clock) {
    return new MongoIdempotency(mongo, clock);
  }

  /** Audit ghi bền vào outbox (transaction riêng) — phát lên bus bởi relay. */
  @Bean
  AuditSink auditSink(MongoOutbox outbox, TransactionTemplate tx, Clock clock) {
    return new MongoOutboxAuditSink(outbox, tx, clock);
  }

  /** Transaction không tự tạo collection: tạo collection nền tảng + index (idempotent) lúc khởi động. */
  @Bean
  ApplicationRunner messagingCollections(MongoTemplate mongo) {
    return args -> {
      for (String c : new String[] {MongoMessaging.OUTBOX, MongoMessaging.INBOX, MongoMessaging.IDEMPOTENCY}) {
        if (!mongo.collectionExists(c)) {
          mongo.createCollection(c);
        }
      }
      MongoMessaging.ensureIndexes(mongo);
    };
  }
"""
    extra_imports = "" if name == "clinical" else """import org.shp.web.auth.HttpIdentityLinkClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
"""
    lines = f"""package {pkg}.config;

import java.time.Clock;
{data_imports}import org.shp.events.audit.AuditSink;
import org.shp.web.auth.IdentityLinkClient;
{extra_imports}import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;
/**
 * Bean nền tảng do SA cấu hình sẵn (sinh bởi tools/scaffold/java_service.py). Dev KHÔNG cần sửa file này; logic nghiệp vụ đặt ở các
 * gói khác của service.
 */
@Configuration
class PlatformConfig {{
{identity}
{data_beans}}}
"""
    return lines


MONGO_IDENTITY = """package org.shp.clinical.config;

import java.util.Optional;
import org.bson.Document;
import org.shp.web.auth.IdentityLink;
import org.shp.web.auth.IdentityLinkClient;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Đọc {@code identity_links} (03 §2): {@code _id} = OIDC subject, {@code fhirUser}, {@code disabled}. */
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
    return Optional.of(new IdentityLink(parts[0], parts[1], Boolean.TRUE.equals(d.getBoolean("disabled"))));
  }
}
"""


def application_yml(name: str, db: str) -> str:
    base = f"""# Cấu hình service {name}. Giá trị nhạy cảm (mật khẩu) KHÔNG đặt ở đây: lấy từ biến môi trường (local: dev/.local/connection.env
# hoặc Secret do dev/scripts/deploy.sh tạo; cloud: Key Vault qua CSI).
spring:
  application:
    name: {name}
"""
    if db == "postgres":
        base += f"""  datasource:
    url: ${{SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:15432/{name}}}
    username: ${{SPRING_DATASOURCE_USERNAME:shp}}
    password: ${{SPRING_DATASOURCE_PASSWORD:}}
  flyway:
    # Bảng nền tảng (outbox, inbox, idempotency_key) đến từ shp-data-postgres; migration của service đặt ở db/migration (V1+).
    locations: classpath:db/migration,classpath:db/shp-messaging/postgres
"""
    else:
        base += f"""  mongodb:
    uri: ${{SPRING_MONGODB_URI:mongodb://localhost:27117/?directConnection=true}}
    database: {name}
"""
    base += """shp:
  clinical:
    url: ${SHP_CLINICAL_URL:http://clinical}
server:
  port: 8080
"""
    return base


def test_java(name: str, db: str) -> str:
    c = cls(name)
    clinical_extra = CLINICAL_TEST if name == "clinical" else ""
    if db == "postgres":
        container = """  @Container
  @ServiceConnection
  static final PostgreSQLContainer DB =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"))
              .asCompatibleSubstituteFor("postgres"));"""
        imp = "import org.testcontainers.postgresql.PostgreSQLContainer;"
    else:
        container = """  @Container
  @ServiceConnection
  static final MongoDBContainer DB =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withReplicaSet();"""
        imp = "import org.testcontainers.mongodb.MongoDBContainer;"
    return f"""package org.shp.{name};

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.shp.web.authz.UnprotectedEndpoints;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
{imp}
import org.testcontainers.utility.DockerImageName;

/** Test khung: service khởi động với DB thật, endpoint nền tảng đúng, KHÔNG có endpoint nào thiếu khai báo quyền. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {{"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"}})
@Testcontainers
class {c}ApplicationTest {{
{container}

  @Autowired Environment env;
  @Autowired ApplicationContext ctx;

  private HttpResponse<String> get(String path) throws Exception {{
    var uri = URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
    try (var client = HttpClient.newHttpClient()) {{
      return client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }}
  }}

  @Test
  void platformEndpointsWork() throws Exception {{
    assertThat(get("/health/live").statusCode()).isEqualTo(200);
    assertThat(get("/health/ready").statusCode()).isEqualTo(200);
    assertThat(get("/info").body()).contains("\\"name\\":\\"{name}\\"");
  }}

  @Test
  void everyEndpointDeclaresItsAuthorizationRule() {{
    assertThat(UnprotectedEndpoints.find(ctx)).isEmpty();
  }}

  @Test
  void apiRequiresAToken() throws Exception {{
    var r = get("/api/v1/anything");
    assertThat(r.statusCode()).isEqualTo(401);
    assertThat(r.body()).contains("unauthenticated");
  }}
{clinical_extra}}}
"""


CLINICAL_TEST = '''
  @Autowired org.shp.web.auth.IdentityLinkClient identityLinks;
  @Autowired org.springframework.data.mongodb.core.MongoTemplate mongo;

  @Test
  void identityLinksAreReadFromTheClinicalDatabase() {
    mongo.save(
        new org.bson.Document("_id", "kc-sub-1").append("fhirUser", "Practitioner/p-1").append("disabled", true), "identity_links");
    assertThat(identityLinks.find("kc-sub-1")).hasValue(new org.shp.web.auth.IdentityLink("Practitioner", "p-1", true));
    assertThat(identityLinks.find("nobody")).isEmpty();
  }
'''


def dockerfile(name: str) -> str:
    return f"""# syntax=docker/dockerfile:1
# Build context = gốc repo:  docker build -f services/{name}/Dockerfile .
FROM mirror.gcr.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl services/{name} -am package -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true

FROM gcr.io/distroless/java21-debian13:nonroot
WORKDIR /app
COPY --from=build /src/services/{name}/target/app.jar /app/app.jar
EXPOSE 8080
USER nonroot
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
"""


def values_local(name: str, db: str) -> str:
    s = f"""# Values riêng của {name} khi chạy trên kind (make -C dev deploy SVC={name}); nạp sau dev/chart/app-values-local.yaml.
image:
  repository: localhost/shp-{name}
  tag: dev
env:
  OTEL_SERVICE_NAME: {name}
"""
    if db == "postgres":
        s += f"  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres.shp-deps.svc.cluster.local:5432/{name}\n  SPRING_DATASOURCE_USERNAME: shp\n"
    else:
        s += "  SPRING_MONGODB_URI: mongodb://mongodb.shp-deps.svc.cluster.local:27017/?directConnection=true\n"
    return s


def readme(name: str, owner: str, desc: str, db: str) -> str:
    dbtxt = "PostgreSQL (Flyway: `src/main/resources/db/migration/V1__*.sql`)" if db == "postgres" else f"MongoDB (database `{name}`)"
    return f"""# {name}

{desc}. Chủ sở hữu: **{owner}**. Lưu trữ: {dbtxt}.

| Lệnh (chạy ở gốc repo) | Việc |
|---|---|
| `mvn -B -pl services/{name} -am verify` | build + test như CI (cần Docker cho Testcontainers) |
| `set -a; . dev/.local/connection.env; set +a; mvn -pl services/{name} -am spring-boot:run` | chạy local với hạ tầng của `make -C dev up` |
| `make -C dev deploy SVC={name}` | build image và deploy lên cụm kind |

## Dev viết gì, ở đâu
- Bố cục gói (`docs/03-architecture/overview.md` §4): `api/` (controller) → `app/` (use case, transaction) → `domain/` (quy tắc, không Spring)
  → `infra/` (repository, client); `messaging/` (consumer); `config/` của SA.
- Endpoint: `src/main/java/org/shp/{name}/api/*Controller.java`. **Mọi endpoint** phải có `@RequireShpAuthz(resource, action, context)`
  (test `everyEndpointDeclaresItsAuthorizationRule` đỏ nếu quên). Dữ kiện cho phân quyền: bean `ResourceContextResolver`.
- Lỗi nghiệp vụ: `throw new ShpException("slot-unavailable")` (mã trong `contracts` + 07 §5).
- Event: `outbox.add(CloudEvents.create("{name}", "shp.<context>.<event>.v1", subject, payload))` **trong transaction** của thay đổi dữ liệu;
  test hợp đồng: `EventSchemas.validate(...)` rỗng.
- Consumer: `inbox.handleOnce("{name}", event, () -> ...)`.
- Idempotency-Key: `idempotency.claim(subject, IdempotencyKeys.require(header), operation, IdempotencyKeys.hashBody(body))`.
- Bean nền tảng đã cấu hình sẵn ở `config/PlatformConfig.java` — không cần sửa.

Tài liệu: `docs/03-architecture/detailed-design/` (API, DB, event của service) và `docs/05-plan/by-person/{owner}.md` (task).
"""


def workflow(name: str) -> str:
    return f"""# Generated by tools/scaffold/java_service.py. Pipeline: CI (Maven verify) -> image (scan, SBOM, sign) -> promote to uat (awaits approval).
name: svc-{name}
on:
  pull_request:
    paths: ["services/{name}/**", "libs/java/**", "contracts/**", "pom.xml", ".github/workflows/svc-{name}.yml"]
  push:
    branches: [main]
    paths: ["services/{name}/**", "libs/java/**", "contracts/**", "pom.xml", ".github/workflows/svc-{name}.yml"]
permissions:
  contents: read
jobs:
  ci:
    uses: {TEMPLATES_REPO}/.github/workflows/java-ci.yml@{TEMPLATES_REF}
    with:
      modules: services/{name}
  container:
    needs: ci
    uses: {TEMPLATES_REPO}/.github/workflows/container.yml@{TEMPLATES_REF}
    permissions:
      contents: read
      id-token: write
    with:
      context: .
      dockerfile: services/{name}/Dockerfile
      image-name: {name}
      push: ${{{{ github.ref == 'refs/heads/main' }}}}
      acr-name: ${{{{ vars.ACR_NAME }}}}
      azure-client-id: ${{{{ vars.AZURE_CLIENT_ID_ACR_PUSH }}}}
      azure-tenant-id: ${{{{ vars.AZURE_TENANT_ID }}}}
      azure-subscription-id: ${{{{ vars.AZURE_SUBSCRIPTION_ID }}}}
  promote-uat:
    if: github.ref == 'refs/heads/main'
    needs: container
    uses: {TEMPLATES_REPO}/.github/workflows/gitops-promote.yml@{TEMPLATES_REF}
    with:
      service: {name}
      environment: uat
      digest: ${{{{ needs.container.outputs.digest }}}}
      gitops-owner: viethung12062005
      gitops-repo: shp-gitops
      gitops-app-id: ${{{{ vars.GITOPS_APP_ID }}}}
      mode: commit
    secrets:
      gitops-app-private-key: ${{{{ secrets.GITOPS_APP_PRIVATE_KEY }}}}
"""


# Bố cục gói chuẩn (docs/03-architecture/overview.md §4); api/ và config/ sinh riêng ở trên.
LAYERS = {
    "app": "Use case: nơi duy nhất mở transaction (@Transactional); ghi dữ liệu và outbox.add(...) trong cùng transaction.",
    "domain": "Model và quy tắc nghiệp vụ; KHÔNG import Spring/JDBC/Mongo (test bằng JUnit thuần).",
    "infra": "Repository (JDBC/Mongo), client REST nội bộ, Blob — chỉ app/ gọi tới.",
    "messaging": "Consumer event: mỗi handler bọc trong inbox.handleOnce(...) rồi gọi use case ở app/.",
}


def files(name: str) -> dict[str, str]:
    owner, desc, db = SERVICES[name]
    base = f"services/{name}"
    pkg = f"{base}/src/main/java/org/shp/{name}"
    out = {
        f"{base}/pom.xml": pom(name, desc, db),
        f"{pkg}/{cls(name)}Application.java": f"""package org.shp.{name};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** {desc}. */
@SpringBootApplication
public class {cls(name)}Application {{
  public static void main(String[] args) {{
    SpringApplication.run({cls(name)}Application.class, args);
  }}
}}
""",
        f"{pkg}/config/PlatformConfig.java": platform_config(name, db),
        f"{pkg}/api/package-info.java": f"""/** Controller REST/FHIR của {name}. Mỗi endpoint: {{@code @RequireShpAuthz(...)}} (xem README của service). */
package org.shp.{name}.api;
""",
        **{f"{pkg}/{sub}/package-info.java": f"/** {doc} */\npackage org.shp.{name}.{sub};\n" for sub, doc in LAYERS.items()},
        f"{base}/src/main/resources/application.yml": application_yml(name, db),
        f"{base}/src/test/java/org/shp/{name}/{cls(name)}ApplicationTest.java": test_java(name, db),
        f"{base}/Dockerfile": dockerfile(name),
        f"{base}/deploy/values-local.yaml": values_local(name, db),
        f"{base}/README.md": readme(name, owner, desc, db),
        f".github/workflows/svc-{name}.yml": workflow(name),
    }
    if db == "postgres":
        out[f"{base}/src/main/resources/db/migration/README.md"] = (
            "Migration Flyway của service: `V1__<mo_ta>.sql`, `V2__...` (không sửa file đã merge). DDL nguồn: "
            "`docs/03-architecture/detailed-design/02-db-scheduling.md`.\n"
        )
    if name == "clinical":
        out[f"{pkg}/config/MongoIdentityLinkClient.java"] = MONGO_IDENTITY
    return out


def main(argv: list[str]) -> int:
    for name in argv or list(SERVICES):
        if name not in SERVICES:
            print(f"unknown service: {name}", file=sys.stderr)
            return 2
        for rel, content in files(name).items():
            path = ROOT / rel
            if path.exists():
                print(f"skip   {rel}")
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            print(f"create {rel}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
