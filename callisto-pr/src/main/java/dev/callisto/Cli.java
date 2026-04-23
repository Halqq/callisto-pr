package dev.callisto;

import dev.callisto.cli.DraftPrCommand;
import dev.callisto.cli.ShowCommand;
import dev.callisto.cli.SummaryCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Callisto CLI entry point (standalone java -jar mode).
 *
 * <p>Phase 1 stub — prints usage via picocli.
 * Full subcommands (summary, list, show, draft-pr) are added in Phase 4+.
 *
 * <p>D-21: --llm, --model, --api-key propagate as system properties before any
 * ConfigLoader.load() is invoked by subcommands. For automation, prefer
 * callisto.json or env variables over --api-key to avoid key exposure in ps aux.
 */
@Command(
    name = "callisto",
    mixinStandardHelpOptions = true,
    version = "1.0.0-SNAPSHOT",
    description = "Callisto — Runtime exception classifier",
    subcommands = { SummaryCommand.class, DraftPrCommand.class, ShowCommand.class }
)
public class Cli implements Runnable {

    @Option(
        names = "--llm",
        description = "LLM provider override: anthropic | openai | deepseek (sets -Dcallisto.llm.provider)",
        paramLabel = "PROVIDER"
    )
    private String llmProvider;

    @Option(
        names = "--model",
        description = "LLM model override, e.g. gpt-4o (sets -Dcallisto.llm.model)",
        paramLabel = "MODEL"
    )
    private String llmModel;

    @Option(
        names = "--api-key",
        description = "LLM API key override (sets -Dcallisto.llm.apiKey). Prefer callisto.json or env var for automation to avoid key exposure in process list.",
        paramLabel = "KEY"
    )
    private String llmApiKey;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    public static void main(String[] args) {
        System.exit(new CommandLine(new Cli())
                .setExecutionStrategy(new CommandLine.RunAll())
                .setExitCodeExceptionMapper(ex -> 1)
                .execute(args));
    }

    @Override
    public void run() {
        if (llmProvider != null && !llmProvider.isEmpty()) {
            System.setProperty("callisto.llm.provider", llmProvider);
        }
        if (llmModel != null && !llmModel.isEmpty()) {
            System.setProperty("callisto.llm.model", llmModel);
        }
        if (llmApiKey != null && !llmApiKey.isEmpty()) {
            System.setProperty("callisto.llm.apiKey", llmApiKey);
        }
        boolean hasSubcommand = !spec.commandLine().getParseResult().subcommands().isEmpty();
        if (!hasSubcommand && llmProvider == null && llmModel == null && llmApiKey == null) {
            System.out.println("Callisto CLI — use --help for available commands");
        }
    }
}
