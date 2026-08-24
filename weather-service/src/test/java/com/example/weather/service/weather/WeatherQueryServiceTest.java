package com.example.weather.service.weather;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeatherQueryServiceTest {

    private WeatherQueryService service;

    @BeforeEach
    void setUp() {
        service = new WeatherQueryService(new InMemoryWeatherRepository());
    }

    @Test
    void returnsWeatherForChineseCityName() {
        WeatherReading result = service.getCurrentWeather("北京");
        assertThat(result.city()).isEqualTo("北京");
        assertThat(result.condition()).isEqualTo("晴");
    }

    @Test
    void mapsEnglishAliasIgnoringCaseAndWhitespace() {
        WeatherReading result = service.getCurrentWeather("  ShAnGhAi  ");
        assertThat(result.city()).isEqualTo("上海");
    }

    @Test
    void rejectsBlankCity() {
        assertThatThrownBy(() -> service.getCurrentWeather("  "))
                .isInstanceOf(InvalidCityException.class)
                .hasMessage("城市名称不能为空");
    }

    @Test
    void rejectsUnsupportedCity() {
        assertThatThrownBy(() -> service.getCurrentWeather("Atlantis"))
                .isInstanceOf(UnsupportedCityException.class)
                .hasMessage("暂不支持城市：Atlantis");
    }
}
