package com.example.weather.service.weather;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

@Repository
public final class InMemoryWeatherRepository implements WeatherRepository {

    private static final WeatherReading BEIJING = new WeatherReading(
            "北京", "晴", new BigDecimal("26.5"), new BigDecimal("27.1"), 42, new BigDecimal("10.8"));
    private static final WeatherReading SHANGHAI = new WeatherReading(
            "上海", "多云", new BigDecimal("24.0"), new BigDecimal("25.2"), 68, new BigDecimal("14.4"));
    private static final WeatherReading GUANGZHOU = new WeatherReading(
            "广州", "阵雨", new BigDecimal("30.2"), new BigDecimal("34.0"), 78, new BigDecimal("8.6"));
    private static final WeatherReading SHENZHEN = new WeatherReading(
            "深圳", "多云", new BigDecimal("29.4"), new BigDecimal("32.1"), 74, new BigDecimal("12.2"));
    private static final WeatherReading HANGZHOU = new WeatherReading(
            "杭州", "小雨", new BigDecimal("23.6"), new BigDecimal("24.3"), 81, new BigDecimal("9.5"));

    private final Map<String, WeatherReading> readings = Map.ofEntries(
            Map.entry("北京", BEIJING), Map.entry("beijing", BEIJING),
            Map.entry("上海", SHANGHAI), Map.entry("shanghai", SHANGHAI),
            Map.entry("广州", GUANGZHOU), Map.entry("guangzhou", GUANGZHOU),
            Map.entry("深圳", SHENZHEN), Map.entry("shenzhen", SHENZHEN),
            Map.entry("杭州", HANGZHOU), Map.entry("hangzhou", HANGZHOU));

    @Override
    public Optional<WeatherReading> findByAlias(String normalizedAlias) {
        return Optional.ofNullable(readings.get(normalizedAlias));
    }
}
