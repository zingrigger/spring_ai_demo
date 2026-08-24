package com.example.weather.service.weather;

import java.util.Locale;

import org.springframework.stereotype.Service;

@Service
public final class WeatherQueryService {

    private final WeatherRepository repository;

    public WeatherQueryService(WeatherRepository repository) {
        this.repository = repository;
    }

    public WeatherReading getCurrentWeather(String city) {
        if (city == null || city.isBlank()) {
            throw new InvalidCityException();
        }
        String strippedCity = city.strip();
        String normalizedAlias = strippedCity.toLowerCase(Locale.ROOT);
        return repository.findByAlias(normalizedAlias)
                .orElseThrow(() -> new UnsupportedCityException(strippedCity));
    }
}
