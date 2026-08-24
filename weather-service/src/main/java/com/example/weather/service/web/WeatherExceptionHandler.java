package com.example.weather.service.web;

import com.example.weather.service.weather.InvalidCityException;
import com.example.weather.service.weather.UnsupportedCityException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class WeatherExceptionHandler {

    @ExceptionHandler(InvalidCityException.class)
    ProblemDetail handleInvalidCity(InvalidCityException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid city");
        return problem;
    }

    @ExceptionHandler(UnsupportedCityException.class)
    ProblemDetail handleUnsupportedCity(UnsupportedCityException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Unsupported city");
        problem.setProperty("city", exception.city());
        return problem;
    }
}
