package dev.callisto.cli;

import dev.callisto.model.BugRecord;
import dev.callisto.store.BugStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * callisto summary — shows statistics of captured bugs.
 *
 * CLI-01: total count + classification (INTERNAL/EXTERNAL/UNCERTAIN) + top 5 exception types.
 * D-23: ANSI colors via picocli Ansi.AUTO (auto-stripped outside TTY).
 * D-24: simple class name, not FQCN.
 * D-25: top 5 by BugRecord count (not occurrenceCount).
 * D-26: --dir flag, default CWD, exit 1 if bugs.jsonl absent.
 */
@Command(
    name = "summary",
    description = "Print captured bug statistics"
)
public class SummaryCommand implements Runnable {

    @Option(
        names = "--dir",
        description = "Project directory containing .callisto/ (default: current working directory)",
        defaultValue = "${sys:user.dir}",
        paramLabel = "PATH"
    )
    private String dir;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    private static final int BAR_WIDTH = 8;

    @Override
    public void run() {
        Path bugsFile = Paths.get(dir).resolve(".callisto").resolve("bugs.jsonl");
        if (!Files.exists(bugsFile)) {
            PrintWriter err = spec.commandLine().getErr();
            err.println("No bugs.jsonl found at " + Paths.get(dir).resolve(".callisto")
                + " — is Callisto attached to a project there?");
            err.flush();
            throw new CommandLine.ExecutionException(spec.commandLine(), "bugs.jsonl not found");
        }

        List<BugRecord> records = BugStore.readAll(bugsFile);
        printSummary(records);
        spec.commandLine().getOut().flush();
    }

    private void printSummary(List<BugRecord> records) {
        long total = records.size();
        PrintWriter out = spec.commandLine().getOut();

        out.println("Callisto Summary");
        out.println("================");
        out.println("Total bugs:  " + total);
        out.println();

        if (total == 0) {
            out.println("(no bugs captured yet)");
            out.flush();
            return;
        }

        // Classification breakdown (D-23: INTERNAL=red, EXTERNAL=yellow, UNCERTAIN=plain)
        Map<String, Long> byClass = records.stream().collect(
            Collectors.groupingBy(
                r -> r.getClassification() != null ? r.getClassification() : "UNCERTAIN",
                Collectors.counting()
            )
        );

        out.println("By classification:");
        printClassLine(out, "INTERNAL", byClass.getOrDefault("INTERNAL", 0L), total);
        printClassLine(out, "EXTERNAL", byClass.getOrDefault("EXTERNAL", 0L), total);
        printClassLine(out, "UNCERTAIN", byClass.getOrDefault("UNCERTAIN", 0L), total);
        out.println();

        // Top 5 exception types by BugRecord count (D-25)
        Map<String, Long> topTypes = records.stream().collect(
            Collectors.groupingBy(
                r -> simpleName(r.getExceptionType()),
                Collectors.counting()
            )
        );
        List<Map.Entry<String, Long>> sorted = topTypes.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(5)
            .collect(Collectors.toList());

        out.println("Top exception types:");
        for (Map.Entry<String, Long> entry : sorted) {
            out.printf("  %-35s %d%n", entry.getKey(), entry.getValue());
        }

        // D-11: Fixes section — only shown if any bug has a non-null fix_status
        long fixCount = records.stream().filter(r -> r.getFixStatus() != null).count();
        if (fixCount > 0) {
            out.println();
            out.println("Fixes:");
            long validCount = records.stream().filter(r -> "VALID".equals(r.getFixStatus())).count();
            long invalidCount = records.stream().filter(r -> "INVALID".equals(r.getFixStatus())).count();
            long pendingCount = records.stream()
                .filter(r -> r.getFixStatus() != null && "PENDING".equals(r.getFixStatus())).count();
            out.printf("  %-10s : %d%n", Ansi.AUTO.string("@|green VALID|@"), validCount);
            out.printf("  %-10s : %d%n", Ansi.AUTO.string("@|red INVALID|@"), invalidCount);
            out.printf("  %-10s : %d%n", "PENDING", pendingCount);
        }
        out.flush();
    }

    private void printClassLine(PrintWriter out, String label, long count, long total) {
        String bar = bar(count, total, BAR_WIDTH);
        int pct = total == 0 ? 0 : (int) Math.round((double) count / total * 100);
        String coloredLabel = colorLabel(label);
        String padding = " ".repeat(Math.max(0, 10 - label.length()));
        out.printf("  %s%s %3d  (%s %d%%)%n", coloredLabel, padding, count, bar, pct);
    }

    private String colorLabel(String label) {
        switch (label) {
            case "INTERNAL": return Ansi.AUTO.string("@|red INTERNAL|@");
            case "EXTERNAL": return Ansi.AUTO.string("@|yellow EXTERNAL|@");
            default: return label; // UNCERTAIN = plain
        }
    }

    private String bar(long count, long total, int width) {
        if (total == 0) return " ".repeat(width);
        int filled = (int) Math.round((double) count / total * width);
        return "\u2588".repeat(filled) + " ".repeat(width - filled);
    }

    private static String simpleName(String fqcn) {
        if (fqcn == null) return "Unknown";
        return fqcn.contains(".") ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
    }
}
