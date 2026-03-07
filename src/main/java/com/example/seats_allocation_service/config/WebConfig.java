package com.example.seats_allocation_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final InternalApiAccessInterceptor internalApiAccessInterceptor;

    public WebConfig(InternalApiAccessInterceptor internalApiAccessInterceptor) {
        this.internalApiAccessInterceptor = internalApiAccessInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalApiAccessInterceptor)
                .addPathPatterns("/internal/events/**");
    }
}
