package dev.callisto.llm;

import dev.callisto.model.BugRecord;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuleClassifierTest {

    private BugRecord recordWith(String exceptionType) {
        BugRecord r = new BugRecord();
        r.setExceptionType(exceptionType);
        return r;
    }

    @Test
    void socketTimeoutException_returnsExternal() {
        RuleClassifier rc = new RuleClassifier();
        ClassificationResult result = rc.tryClassify(recordWith("java.net.SocketTimeoutException"));
        assertNotNull(result);
        assertEquals("EXTERNAL", result.classification);
    }

    @Test
    void sslHandshakeException_returnsExternal() {
        RuleClassifier rc = new RuleClassifier();
        ClassificationResult result = rc.tryClassify(recordWith("javax.net.ssl.SSLHandshakeException"));
        assertNotNull(result);
        assertEquals("EXTERNAL", result.classification);
    }

    @Test
    void unknownHostException_returnsExternal() {
        RuleClassifier rc = new RuleClassifier();
        ClassificationResult result = rc.tryClassify(recordWith("java.net.UnknownHostException"));
        assertNotNull(result);
        assertEquals("EXTERNAL", result.classification);
    }

    @Test
    void connectException_returnsExternal() {
        RuleClassifier rc = new RuleClassifier();
        ClassificationResult result = rc.tryClassify(recordWith("java.net.ConnectException"));
        assertNotNull(result);
        assertEquals("EXTERNAL", result.classification);
    }

    @Test
    void nullPointerException_returnsNull() {
        RuleClassifier rc = new RuleClassifier();
        assertNull(rc.tryClassify(recordWith("java.lang.NullPointerException")));
    }

    @Test
    void nullExceptionType_returnsNull() {
        RuleClassifier rc = new RuleClassifier();
        assertNull(rc.tryClassify(recordWith(null)));
    }
}
