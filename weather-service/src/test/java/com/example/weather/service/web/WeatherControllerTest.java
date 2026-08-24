package com.example.weather.service.web;

import java.math.BigDecimal;

import com.example.weather.service.weather.InvalidCityException;
import com.example.weather.service.weather.UnsupportedCityException;
import com.example.weather.service.weather.WeatherQueryService;
import com.example.weather.service.weather.WeatherReading;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WeatherController.class)
@Import(WeatherExceptionHandler.class)
class WeatherControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WeatherQueryService weatherQueryService;

    @Test
    void returnsStructuredWeather() throws Exception {
        WeatherReading reading = new WeatherReading("北京", "晴", new BigDecimal("26.5"),
                new BigDecimal("27.1"), 42, new BigDecimal("10.8"));
        when(weatherQueryService.getCurrentWeather("北京")).thenReturn(reading);

        mockMvc.perform(get("/api/weather/{city}", "北京"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("北京"))
                .andExpect(jsonPath("$.condition").value("晴"))
                .andExpect(jsonPath("$.temperatureCelsius").value(26.5))
                .andExpect(jsonPath("$.feelsLikeCelsius").value(27.1))
                .andExpect(jsonPath("$.humidityPercent").value(42))
                .andExpect(jsonPath("$.windSpeedKph").value(10.8));
    }

    @Test
    void returnsBadRequestForBlankCity() throws Exception {
        when(weatherQueryService.getCurrentWeather(" ")).thenThrow(new InvalidCityException());
        mockMvc.perform(get("/api/weather/{city}", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid city"))
                .andExpect(jsonPath("$.detail").value("城市名称不能为空"));
    }

    @Test
    void returnsNotFoundForUnsupportedCity() throws Exception {
        when(weatherQueryService.getCurrentWeather("Atlantis")).thenThrow(new UnsupportedCityException("Atlantis"));
        mockMvc.perform(get("/api/weather/{city}", "Atlantis"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unsupported city"))
                .andExpect(jsonPath("$.detail").value("暂不支持城市：Atlantis"))
                .andExpect(jsonPath("$.city").value("Atlantis"));
    }
}
