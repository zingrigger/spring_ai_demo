package com.example.weather.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class WeatherServiceApplicationTest {

    @Autowired
    private Environment environment;

    @Test
    void loadsWeatherServiceConfiguration() {
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("weather-service");
        assertThat(environment.getProperty("server.port")).isEqualTo("8082");
    }
}
