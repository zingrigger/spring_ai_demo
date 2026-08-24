package com.example.weather.mcp.tool;

import java.math.BigDecimal;

public record WeatherToolResult(
        String city,
        String condition,
        BigDecimal temperatureCelsius,
        BigDecimal feelsLikeCelsius,
        int humidityPercent,
        BigDecimal windSpeedKph) {
}
