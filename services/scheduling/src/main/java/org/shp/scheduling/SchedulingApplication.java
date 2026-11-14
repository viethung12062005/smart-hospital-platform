package org.shp.scheduling;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Doctor schedules, slots, booking/cancellation of appointments (FHIR Schedule/Slot/Appointment).
 */
@SpringBootApplication
public class SchedulingApplication {
  public static void main(String[] args) {
    SpringApplication.run(SchedulingApplication.class, args);
  }
}
