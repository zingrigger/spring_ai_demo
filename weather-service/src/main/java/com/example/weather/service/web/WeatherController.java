package com.example.weather.service.web;

import com.example.weather.service.weather.WeatherQueryService;
import com.example.weather.service.weather.WeatherReading;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
public final class WeatherController {

    private final WeatherQueryService weatherQueryService;

    public WeatherController(WeatherQueryService weatherQueryService) {
        this.weatherQueryService = weatherQueryService;
    }

    @GetMapping("/{city}")
    public WeatherReading getCurrentWeather(@PathVariable String city) {
        return weatherQueryService.getCurrentWeather(city);
    }
}
