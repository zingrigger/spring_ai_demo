package com.example.weather.service.weather;

public final class UnsupportedCityException extends RuntimeException {

    private final String city;

    public UnsupportedCityException(String city) {
        super("暂不支持城市：" + city);
        this.city = city;
    }

    public String city() {
        return city;
    }
}
