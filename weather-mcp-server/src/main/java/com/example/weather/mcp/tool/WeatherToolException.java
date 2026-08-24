package com.example.weather.mcp.tool;

public final class WeatherToolException extends RuntimeException {

    public WeatherToolException(String message) {
        super(message);
    }

    public WeatherToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
