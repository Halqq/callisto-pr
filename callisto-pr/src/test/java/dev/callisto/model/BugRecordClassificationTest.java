package dev.callisto.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BugRecordClassificationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void classificationAndReasoning_serializeWhenNonNull() throws Exception {
        BugRecord record = new BugRecord();
        record.setId("BUG-001");
        record.setClassification("INTERNAL");
        record.setReasoning("test reasoning");

        String json = mapper.writeValueAsString(record);
        assertTrue(json.contains("\"classification\""), "JSON must contain classification field");
        assertTrue(json.contains("\"reasoning\""), "JSON must contain reasoning field");
        assertTrue(json.contains("INTERNAL"));
        assertTrue(json.contains("test reasoning"));
    }

    @Test
    void classification_omittedWhenNull() throws Exception {
        BugRecord record = new BugRecord();
        record.setId("BUG-002");

        String json = mapper.writeValueAsString(record);
        assertFalse(json.contains("\"classification\""), "JSON must not contain classification when null");
        assertFalse(json.contains("\"reasoning\""), "JSON must not contain reasoning when null");
    }

    @Test
    void deserialize_classificationField() throws Exception {
        String json = "{\"id\":\"BUG-003\",\"classification\":\"EXTERNAL\",\"reasoning\":\"network error\"}";
        BugRecord record = mapper.readValue(json, BugRecord.class);
        assertEquals("EXTERNAL", record.getClassification());
        assertEquals("network error", record.getReasoning());
    }
}
