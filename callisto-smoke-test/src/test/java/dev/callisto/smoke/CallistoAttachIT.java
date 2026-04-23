package dev.callisto.smoke;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

public class CallistoAttachIT {

    @Test
    void agentAttachesWithoutBreakingTargetApp() throws Exception {
        String agentJar = System.getProperty("callisto.agent.jar");
        assertNotNull(agentJar, "callisto.agent.jar system property must be set by maven-failsafe-plugin");
        assertTrue(new File(agentJar).exists(), "callisto.jar must exist at: " + agentJar);

        String javaHome = System.getProperty("java.home");
        String javaBin = Paths.get(javaHome, "bin", "java").toString();

        // Minimal classpath: only the smoke test app JAR
        // Do NOT inherit Maven process classpath (see Pitfall 3 in RESEARCH.md)
        String smokeAppJar = Paths.get(
            System.getProperty("project.basedir", "."),
            "target",
            "callisto-smoke-test-1.0.0-SNAPSHOT.jar"
        ).toString();

        // Fallback: use test classpath if smoke app JAR does not exist
        String classpath = new File(smokeAppJar).exists()
            ? smokeAppJar
            : System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
            javaBin,
            "-javaagent:" + agentJar,
            "-cp", classpath,
            "dev.callisto.smoke.SmokeTestApp"
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        assertEquals(0, exitCode,
            "Target app must exit with code 0. Output:\n" + output);
        assertFalse(output.contains("ClassNotFoundException"),
            "No ClassNotFoundException allowed — relocation may be broken. Output:\n" + output);
        assertFalse(output.contains("NoClassDefFoundError"),
            "No NoClassDefFoundError allowed. Output:\n" + output);
        assertTrue(output.contains("Callisto attached"),
            "Agent must log 'Callisto attached'. Output:\n" + output);
        assertTrue(output.contains("Hello from target app"),
            "Target app must print its message. Output:\n" + output);
    }
}
