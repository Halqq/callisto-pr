package dev.callisto.cli;

import dev.callisto.model.BugRecord;
import dev.callisto.model.FixValidation;
import dev.callisto.store.BugStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

/**
 * `callisto show <id>` subcommand.
 *
 * REQ-3-06: Displays details of a bug captured by Callisto.
 * - fix_status INVALID: displays last_test_output to help with diagnosis
 * - fix_status VALID: displays pr_url of the created PR
 * - fix_status null (PENDING): displays basic bug information
 */
@Command(
    name = "show",
    description = "Show details of a captured bug"
)
public class ShowCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", description = "Bug ID (e.g. BUG-1a2b3c)", paramLabel = "ID")
    private String bugId;

    @Option(
        names = "--dir",
        description = "Project directory containing .callisto/ (default: current working directory)",
        defaultValue = "${sys:user.dir}",
        paramLabel = "DIR"
    )
    private Path projectDir;

    @Override
    public void run() {
        Path bugsFile = projectDir.resolve(".callisto").resolve("bugs.jsonl");
        PrintWriter out = spec.commandLine().getOut();
        try {
            List<BugRecord> records = BugStore.readAll(bugsFile);
            BugRecord found = null;
            for (BugRecord r : records) {
                if (bugId.equals(r.getId())) {
                    found = r;
                    break;
                }
            }

            if (found == null) {
                spec.commandLine().getErr().println("[Callisto] Bug not found: " + bugId);
                return;
            }

            printBug(found, out);
        } catch (Exception e) {
            spec.commandLine().getErr().println("[Callisto] Error reading bugs: " + e.getMessage());
        }
    }

    private void printBug(BugRecord bug, PrintWriter out) {
        String fixStatus = bug.getFixStatus() != null ? bug.getFixStatus() : "PENDING";
        out.println("ID:          " + bug.getId());
        out.println("Exception:   " + bug.getExceptionType());
        out.println("Fix Status:  " + fixStatus);

        FixValidation fv = bug.getFixValidation();
        if (fv != null) {
            out.println("Attempts:    " + fv.getAttempts());
            // REQ-3-06: INVALID -> display last_test_output
            if ("INVALID".equals(fixStatus) && fv.getLastTestOutput() != null) {
                out.println("--- Test Output ---");
                out.println(fv.getLastTestOutput());
                out.println("-------------------");
            }
            // REQ-3-06: VALID -> display pr_url
            if ("VALID".equals(fixStatus) && fv.getPrUrl() != null) {
                out.println("PR URL:      " + fv.getPrUrl());
            }
        }
        out.flush();
    }
}
