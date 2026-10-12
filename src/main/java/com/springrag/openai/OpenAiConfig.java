package com.springrag.openai;

import com.springrag.common.RagProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class OpenAiConfig {

    /** Explicit connect and read timeouts: a hung provider must not hold request or ingestion threads forever. */
    @Bean
    RestClient openAiRestClient(RestClient.Builder builder, RagProperties props) {
        RagProperties.OpenAi cfg = props.openai();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) cfg.connectTimeout().toMillis());
        factory.setReadTimeout((int) cfg.readTimeout().toMillis());
        return builder.requestFactory(factory).baseUrl(cfg.baseUrl()).build();
    }
}
