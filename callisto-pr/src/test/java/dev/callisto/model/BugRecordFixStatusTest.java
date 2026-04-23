package dev.callisto.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class BugRecordFixStatusTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fixStatusNullOmittedFromJson() throws Exception {
        // D-08: backward compat — records without fix_status must not gain the field
        BugRecord r = new BugRecord();
        r.setId("BUG-aabbcc");
        String json = mapper.writeValueAsString(r);
        assertFalse(json.contains("fix_status"), "fix_status must be absent when null");
        assertFalse(json.contains("fixStatus"), "fixStatus must be absent when null");
    }

    @Test
    void fixStatusPresentWhenSet() throws Exception {
        BugRecord r = new BugRecord();
        r.setId("BUG-aabbcc");
        r.setFixStatus("INVALID");
        String json = mapper.writeValueAsString(r);
        assertTrue(json.contains("INVALID"));
    }

    @Test
    void fixValidationPrUrlOmittedWhenNull() throws Exception {
        FixValidation fv = new FixValidation(2, "test output", null);
        String json = mapper.writeValueAsString(fv);
        assertFalse(json.contains("prUrl"), "prUrl must be absent when null");
        assertTrue(json.contains("test output"));
    }

    @Test
    void roundtripBugRecordWithFixValidation() throws Exception {
        FixValidation fv = new FixValidation(1, "BUILD FAILURE", "https://github.com/x/y/pull/1");
        BugRecord r = new BugRecord();
        r.setId("BUG-aabbcc");
        r.setFixStatus("VALID");
        r.setFixValidation(fv);
        String json = mapper.writeValueAsString(r);
        BugRecord deserialized = mapper.readValue(json, BugRecord.class);
        assertEquals("VALID", deserialized.getFixStatus());
        assertEquals(1, deserialized.getFixValidation().getAttempts());
        assertEquals("https://github.com/x/y/pull/1", deserialized.getFixValidation().getPrUrl());
    }
}
