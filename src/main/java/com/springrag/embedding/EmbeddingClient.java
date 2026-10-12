package com.springrag.embedding;

import java.util.List;

public interface EmbeddingClient {

    /** Returns one vector per input text, in the same order. Implementations batch internally. */
    List<float[]> embed(List<String> texts);
}
