package com.springrag.openai;

import com.springrag.common.RagProperties;
import com.springrag.embedding.EmbeddingClient;
import com.springrag.generation.ChatClient;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * One-command check of the OpenAI configuration: {@code --rag.selftest.enabled=true}.
 * Makes one embeddings call, one chat call and one streamed chat call with the configured key and models,
 * prints the result and exits. It is the quickest way to find a wrong key, model name or dimension.
 */
@Component
@Order(10)
@ConditionalOnProperty(name = "rag.selftest.enabled", havingValue = "true")
public class OpenAiSelfTestRunner implements ApplicationRunner {

    private final EmbeddingClient embeddings;
    private final ChatClient chat;
    private final RagProperties props;
    private final ApplicationContext context;

    public OpenAiSelfTestRunner(EmbeddingClient embeddings, ChatClient chat, RagProperties props, ApplicationContext context) {
        this.embeddings = embeddings;
        this.chat = chat;
        this.props = props;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int failures = 0;
        failures += check("embeddings (" + props.openai().embeddingModel() + ", dimension " + props.embedding().dimension() + ")", () -> {
            List<float[]> vectors = embeddings.embed(List.of("self test"));
            return "returned a vector of " + vectors.get(0).length + " numbers";
        });
        failures += check("chat (" + props.openai().chatModel() + ")", () ->
                "answered: " + chat.complete("Reply with the single word OK.", "ping").strip());
        failures += check("streaming chat", () -> {
            AtomicInteger pieces = new AtomicInteger();
            String text = chat.stream("Reply with the single word OK.", "ping", piece -> pieces.incrementAndGet());
            return "received " + pieces.get() + " piece(s), text: " + text.strip();
        });
        System.out.println(failures == 0 ? "\nAll OpenAI checks passed." : "\n" + failures + " OpenAI check(s) failed.");
        int code = failures == 0 ? 0 : 1;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    private interface Check {
        String run();
    }

    private static int check(String name, Check check) {
        try {
            System.out.println("PASS  " + name + ": " + check.run());
            return 0;
        } catch (RuntimeException e) {
            System.out.println("FAIL  " + name + ": " + e.getMessage());
            return 1;
        }
    }
}
