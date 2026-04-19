package com.example.seats_allocation_service.config;

import com.example.seats_allocation_service.service.JWTService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
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
                .addPathPatterns("/internal/seats/**");
    }

    @Bean
    public FilterRegistrationBean<EventSeatsJwtAuthenticationFilter> eventSeatsJwtAuthenticationFilterRegistration(
            JWTService jwtService
    ) {
        FilterRegistrationBean<EventSeatsJwtAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new EventSeatsJwtAuthenticationFilter(jwtService));
        registration.addUrlPatterns("/events/*");
        registration.addUrlPatterns("/events/*/seats");
        registration.addUrlPatterns("/events/*/availability");
        registration.setOrder(1);
        return registration;
    }
}
