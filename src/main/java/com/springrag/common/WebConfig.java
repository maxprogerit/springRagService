package com.springrag.common;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Serve the single-page frontend (static/index.html) at the site root. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /**
     * MAX_UPLOAD_MB limits the file itself. The request limit is 1 MB higher because the multipart envelope
     * (boundaries, headers) also counts, so a file just under the limit is not rejected by mistake.
     */
    @Bean
    MultipartConfigElement multipartConfigElement(RagProperties props) {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofMegabytes(props.maxUploadMb()));
        factory.setMaxRequestSize(DataSize.ofMegabytes(props.maxUploadMb() + 1L));
        return factory.createMultipartConfig();
    }
}
