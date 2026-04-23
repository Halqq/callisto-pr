package dev.callisto.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Tests for package labeling logic — covers AGENT-04, D-11, D-12, D-13.
 * WAVE 0: class PackageLabeler does not exist yet — fails at compile time.
 */
class PackageLabelTest {

    @Test
    void internalPrefix_markedInternal() {
        PackageLabeler labeler = new PackageLabeler(Arrays.asList("com.myapp", "io.mycompany"));
        assertEquals("INTERNAL", labeler.label("com.myapp.Service"),
            "Class com.myapp.* must be INTERNAL");
    }

    @Test
    void externalClass_markedExternal() {
        PackageLabeler labeler = new PackageLabeler(Arrays.asList("com.myapp"));
        assertEquals("EXTERNAL", labeler.label("org.springframework.web.Foo"),
            "Class without configured prefix must be EXTERNAL");
    }

    @Test
    void emptyPrefixList_returnsNull() {
        PackageLabeler labeler = new PackageLabeler(Collections.emptyList());
        assertNull(labeler.label("com.myapp.Service"),
            "Without configured prefix, packageSource must be null (omitted from JSONL)");
    }

    @Test
    void nullPrefixList_returnsNull() {
        PackageLabeler labeler = new PackageLabeler(null);
        assertNull(labeler.label("com.myapp.Service"),
            "Null prefix is equivalent to empty list — packageSource omitted");
    }

    @Test
    void multiplePrefix_anyMatchIsInternal() {
        PackageLabeler labeler = new PackageLabeler(Arrays.asList("com.a", "com.b"));
        assertEquals("INTERNAL", labeler.label("com.b.Controller"),
            "Any prefix in the list causes an INTERNAL match");
    }
}
