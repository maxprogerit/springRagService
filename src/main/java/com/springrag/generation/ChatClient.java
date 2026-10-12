package com.springrag.generation;

import java.util.function.Consumer;

public interface ChatClient {

    /** Single-turn completion; returns the model's text. */
    String complete(String systemPrompt, String userPrompt);

    /**
     * Like {@link #complete} but hands every piece of text to {@code onDelta} as soon as the model produces it.
     * Returns the complete text. The default implementation delivers everything at once.
     */
    default String stream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        String full = complete(systemPrompt, userPrompt);
        onDelta.accept(full);
        return full;
    }
}
