package org.shp.clinical;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Patient records, encounters, history search, account linking (FHIR). */
@SpringBootApplication
public class ClinicalApplication {
  public static void main(String[] args) {
    SpringApplication.run(ClinicalApplication.class, args);
  }
}
