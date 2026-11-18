package org.shp.audit;

import java.security.SecureRandom;
import java.util.Base64;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared container recipes for the audit tests: MongoDB replica set and Azurite (Blob). */
public final class AuditTestContainers {
  public static final String ACCOUNT = "shp";
  public static final int BLOB_PORT = 10000;
  // A random account key per run: nothing secret or well-known lives in the repository.
  public static final String KEY = randomKey();

  private AuditTestContainers() {}

  public static MongoDBContainer mongo() {
    return new MongoDBContainer(
            DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                .asCompatibleSubstituteFor("mongo"))
        .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1") // SERVER-121912, see MongoTestBase
        .withReplicaSet();
  }

  public static GenericContainer<?> azurite() {
    return new GenericContainer<>(
            DockerImageName.parse(
                System.getenv()
                    .getOrDefault(
                        "SHP_TEST_AZURITE_IMAGE",
                        "mcr.microsoft.com/azure-storage/azurite:3.37.0")))
        .withEnv("AZURITE_ACCOUNTS", ACCOUNT + ":" + KEY)
        .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck")
        .withExposedPorts(BLOB_PORT);
  }

  public static String connectionString(GenericContainer<?> azurite) {
    return "DefaultEndpointsProtocol=http;AccountName="
        + ACCOUNT
        + ";AccountKey="
        + KEY
        + ";BlobEndpoint=http://"
        + azurite.getHost()
        + ":"
        + azurite.getMappedPort(BLOB_PORT)
        + "/"
        + ACCOUNT;
  }

  private static String randomKey() {
    byte[] key = new byte[64];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }
}
