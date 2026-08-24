package com.example.weather.service.weather;

import java.util.Optional;

public interface WeatherRepository {

    Optional<WeatherReading> findByAlias(String normalizedAlias);
}
