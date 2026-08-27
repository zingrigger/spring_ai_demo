package com.example.weather.mcp.tool;

import com.example.weather.mcp.client.WeatherServiceClient;
import com.example.weather.mcp.client.WeatherServiceResponse;
import feign.FeignException;
import feign.RetryableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

@Component
public class WeatherTool {

    private static final Logger logger = LoggerFactory.getLogger(WeatherTool.class);
    private static final String SERVICE_UNAVAILABLE = "天气服务暂时不可用，请稍后重试";

    private final WeatherServiceClient weatherServiceClient;

    public WeatherTool(WeatherServiceClient weatherServiceClient) {
        this.weatherServiceClient = weatherServiceClient;
    }

    @PreAuthorize("hasAuthority('SCOPE_weather:read')")
    @Tool(name = "get_weather_by_city", description = "查询指定城市的当前天气")
    public WeatherToolResult getWeatherByCity(
            @ToolParam(description = "城市名称，例如北京或 Beijing") String city) {
        logger.info("打印 tool - get_weather_by_city 参数：{}", city);
        if (city == null || city.isBlank()) {
            throw new WeatherToolException("城市名称不能为空");
        }
        String strippedCity = city.strip();
        try {
            return toToolResult(weatherServiceClient.getCurrentWeather(strippedCity));
        }
        catch (RetryableException exception) {
            logger.warn("Weather service unavailable for city {}", strippedCity, exception);
            throw new WeatherToolException(SERVICE_UNAVAILABLE, exception);
        }
        catch (FeignException exception) {
            if (exception.status() == 404) {
                throw new WeatherToolException("暂不支持城市：" + strippedCity, exception);
            }
            if (exception.status() == 503 || exception.status() == 504) {
                logger.warn("Weather service unavailable for city {}", strippedCity, exception);
                throw new WeatherToolException(SERVICE_UNAVAILABLE, exception);
            }
            logger.error("Weather service request failed for city {}", strippedCity, exception);
            throw new WeatherToolException("查询天气失败", exception);
        }
        catch (RuntimeException exception) {
            logger.error("Unexpected weather tool failure for city {}", strippedCity, exception);
            throw new WeatherToolException("查询天气失败", exception);
        }
    }

    private WeatherToolResult toToolResult(WeatherServiceResponse response) {
        return new WeatherToolResult(response.city(), response.condition(), response.temperatureCelsius(),
                response.feelsLikeCelsius(), response.humidityPercent(), response.windSpeedKph());
    }
}
