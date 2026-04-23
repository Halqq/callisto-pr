package dev.callisto.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CallistoConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testConfigDefaultMaxAttempts() throws Exception {
        // No "test" field in JSON — TestConfig must use default maxAttempts=3
        CallistoConfig cfg = mapper.readValue("{}", CallistoConfig.class);
        assertEquals(3, cfg.getTest().getMaxAttempts());
        assertNull(cfg.getTest().getCommand());
    }

    @Test
    void testConfigLoadedWithMaxAttempts() throws Exception {
        // REQ-3-05: maxAttempts configurable via callisto.json
        CallistoConfig cfg = mapper.readValue("{\"test\":{\"maxAttempts\":5}}", CallistoConfig.class);
        assertEquals(5, cfg.getTest().getMaxAttempts());
    }

    @Test
    void testConfigLoadedWithCommand() throws Exception {
        // D-01: override via test.command
        CallistoConfig cfg = mapper.readValue("{\"test\":{\"command\":\"npm test\"}}", CallistoConfig.class);
        assertEquals("npm test", cfg.getTest().getCommand());
    }

    @Test
    void testGetTestNeverNull() throws Exception {
        CallistoConfig cfg = mapper.readValue("{}", CallistoConfig.class);
        assertNotNull(cfg.getTest());
    }
}
