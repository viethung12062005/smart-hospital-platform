package org.shp.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** Envelope CloudEvents 1.0 của SHP (13-events §1; schema: contracts/events/envelope.json). */
public record CloudEvent(
    @JsonProperty("specversion") String specVersion,
    @JsonProperty("id") String id,
    @JsonProperty("source") String source,
    @JsonProperty("type") String type,
    @JsonProperty("subject") String subject,
    @JsonProperty("time") Instant time,
    @JsonProperty("datacontenttype") String dataContentType,
    @JsonProperty("dataschema") String dataSchema,
    @JsonProperty("traceparent") String traceParent,
    @JsonProperty("data") JsonNode data) {}
