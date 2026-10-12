package com.springrag.evaluation;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Command-line entry point, active only with {@code --rag.eval.enabled=true}. Uses the configured embedding model
 * (a real OpenAI key is required), prints the report to stdout and exits.
 */
@Component
@Order(10)
@ConditionalOnProperty(name = "rag.eval.enabled", havingValue = "true")
public class EvaluationRunner implements ApplicationRunner {

    private final EvaluationService service;
    private final ApplicationContext context;
    private final String ownerEmail;

    public EvaluationRunner(EvaluationService service, ApplicationContext context,
                            @Value("${rag.eval.owner-email:}") String ownerEmail) {
        this.service = service;
        this.context = context;
        this.ownerEmail = ownerEmail;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try {
            System.out.println();
            System.out.println(service.run(ownerEmail).toMarkdown());
        } catch (RuntimeException e) {
            System.err.println("Evaluation failed: " + e.getMessage());
            exitCode = 1;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
