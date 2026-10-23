package com.springrag.generation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChatTemperatureTest {

    @ParameterizedTest
    @ValueSource(strings = {"gpt-5", "gpt-5-mini", "GPT-5.1", "o1", "o3-mini", "o4-mini"})
    void reasoningModelsGetNoTemperatureUnlessConfigured(String model) {
        assertThat(OpenAiChatClient.temperatureFor(model, null)).isNull();
        assertThat(OpenAiChatClient.temperatureFor(model, 1.0)).isEqualTo(1.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o-mini", "gpt-4.1", "gpt-3.5-turbo", "some-compatible-model", "omni-chat"})
    void ordinaryModelsGetZeroByDefault(String model) {
        assertThat(OpenAiChatClient.temperatureFor(model, null)).isEqualTo(0.0);
        assertThat(OpenAiChatClient.temperatureFor(model, 0.7)).isEqualTo(0.7);
    }
}
