package com.example.weather.service.weather;

public final class InvalidCityException extends RuntimeException {

    public InvalidCityException() {
        super("城市名称不能为空");
    }
}
