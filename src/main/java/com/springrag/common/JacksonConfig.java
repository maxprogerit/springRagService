package com.springrag.common;

import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;

@Configuration
public class JacksonConfig {

    /** Longest JSON string accepted. Jackson's default is 20 million characters, far above any legitimate field here. */
    public static final int MAX_JSON_STRING_LENGTH = 100_000;

    @Bean
    Jackson2ObjectMapperBuilderCustomizer streamReadLimits() {
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder().maxStringLength(MAX_JSON_STRING_LENGTH).build()));
    }
}
