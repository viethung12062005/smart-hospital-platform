package org.shp.document;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Clinical documents: expiring upload/download links, scan status. */
@SpringBootApplication
public class DocumentApplication {
  public static void main(String[] args) {
    SpringApplication.run(DocumentApplication.class, args);
  }
}
