package dev.callisto;

import dev.callisto.agent.CallistoUncaughtHandler;
import dev.callisto.agent.ThrowableInterceptor;
import dev.callisto.config.CallistoConfig;
import dev.callisto.config.ConfigLoader;
import dev.callisto.store.BugStore;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;

/**
 * Callisto Java Agent entry point.
 *
 * Sets up config, creates the {@code .callisto/} output directory,
 * initializes {@link BugStore}, installs the uncaught exception handler,
 * and activates Byte Buddy instrumentation to capture caught exceptions.
 *
 * {@code premain} must never throw — a top-level try/catch ensures the target JVM
 * continues unaffected if Callisto initialization fails.
 */
public class Agent {

    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            // 1. Load config from user.dir/callisto.json
            Path configPath = Path.of(System.getProperty("user.dir")).resolve("callisto.json");
            CallistoConfig config = ConfigLoader.load(configPath);

            // 2+3. Create .callisto/ and read existing bugs.jsonl
            BugStore store = new BugStore(config);
            store.init();

            // 4. Install UncaughtExceptionHandler — ALWAYS after store.init() so the
            //    handler never runs before the store is ready
            CallistoUncaughtHandler.install(store, config);

            // 5. Byte Buddy instrumentation — intercept Throwable constructors to capture
            //    caught exceptions (not just uncaught ones). Must be AFTER install() so
            //    CallistoUncaughtHandler.INSTANCE is set before interception begins.
            new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(isSubTypeOf(Throwable.class))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                    builder.visit(Advice.to(ThrowableInterceptor.class).on(isConstructor())))
                .installOn(inst);

            System.err.println("[Callisto] Callisto attached — storing bugs to " +
                Path.of(System.getProperty("user.dir")).resolve(config.getOutputDir()));
        } catch (Exception e) {
            // Premain NEVER rethrows — agent fails silently; target app continues
            System.err.println("[Callisto] initialization failed: " + e.getMessage());
        }
    }

    /** Fallback for dynamic attach (agentmain) — delegates to premain. */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }
}
