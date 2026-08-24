package com.example.weather.mcp.client;

import java.math.BigDecimal;

public record WeatherServiceResponse(
        String city,
        String condition,
        BigDecimal temperatureCelsius,
        BigDecimal feelsLikeCelsius,
        int humidityPercent,
        BigDecimal windSpeedKph) {
}
