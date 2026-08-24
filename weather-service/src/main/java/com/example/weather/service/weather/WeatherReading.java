package com.example.weather.service.weather;

import java.math.BigDecimal;

public record WeatherReading(
        String city,
        String condition,
        BigDecimal temperatureCelsius,
        BigDecimal feelsLikeCelsius,
        int humidityPercent,
        BigDecimal windSpeedKph) {
}
