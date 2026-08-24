package com.example.weather.mcp.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "weather-service", path = "/api/weather")
public interface WeatherServiceClient {

    @GetMapping("/{city}")
    WeatherServiceResponse getCurrentWeather(@PathVariable("city") String city);
}
