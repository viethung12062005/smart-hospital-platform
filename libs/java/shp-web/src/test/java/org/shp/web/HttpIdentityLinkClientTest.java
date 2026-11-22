package org.shp.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.Test;
import org.shp.authz.LinkedProfile;
import org.shp.web.auth.HttpIdentityLinkClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpIdentityLinkClientTest {
  @Test
  void dropsProfilesWithNullOrMissingRelationshipOrScope() {
    var body =
        """
        {"fhirUser":"Patient/p1","disabled":false,"linkedProfiles":[
          {"patientId":"p2","relationship":null,"scope":"full"},
          {"patientId":"p3","scope":"full"},
          {"patientId":"p4","relationship":"proxy","scope":null},
          {"patientId":"p5","relationship":"proxy"},
          {"patientId":"p6","relationship":"guardian","scope":"full"}
        ]}
        """;
    var builder = RestClient.builder().baseUrl("http://clinical");
    MockRestServiceServer.bindTo(builder)
        .build()
        .expect(requestTo("http://clinical/internal/v1/identity/s"))
        .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(body));

    assertThat(new HttpIdentityLinkClient(builder.build()).find("s").orElseThrow().linkedProfiles())
        .containsExactly(new LinkedProfile("p6", "guardian", "full", null));
  }
}
