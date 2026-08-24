package com.example.weather.mcp.tool;

import java.math.BigDecimal;

import com.example.weather.mcp.client.WeatherServiceClient;
import com.example.weather.mcp.client.WeatherServiceResponse;
import feign.FeignException;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeatherToolTest {

    private WeatherServiceClient client;
    private WeatherTool tool;

    @BeforeEach
    void setUp() {
        client = mock(WeatherServiceClient.class);
        tool = new WeatherTool(client);
    }

    @Test
    void returnsMappedWeatherResult() {
        WeatherServiceResponse response = new WeatherServiceResponse("北京", "晴", new BigDecimal("26.5"),
                new BigDecimal("27.1"), 42, new BigDecimal("10.8"));
        when(client.getCurrentWeather("北京")).thenReturn(response);

        WeatherToolResult result = tool.getWeatherByCity("  北京  ");

        assertThat(result.city()).isEqualTo("北京");
        assertThat(result.temperatureCelsius()).isEqualByComparingTo("26.5");
        verify(client).getCurrentWeather("北京");
    }

    @Test
    void rejectsBlankCityBeforeCallingFeign() {
        assertThatThrownBy(() -> tool.getWeatherByCity(" "))
                .isInstanceOf(WeatherToolException.class).hasMessage("城市名称不能为空");
        verify(client, never()).getCurrentWeather(anyString());
    }

    @Test
    void translatesNotFoundWithoutLeakingFeignDetails() {
        FeignException exception = mock(FeignException.class);
        when(exception.status()).thenReturn(404);
        when(client.getCurrentWeather("Atlantis")).thenThrow(exception);
        assertThatThrownBy(() -> tool.getWeatherByCity("Atlantis"))
                .isInstanceOf(WeatherToolException.class).hasMessage("暂不支持城市：Atlantis");
    }

    @Test
    void translatesNoAvailableInstance() {
        FeignException exception = mock(FeignException.class);
        when(exception.status()).thenReturn(503);
        when(client.getCurrentWeather("北京")).thenThrow(exception);
        assertThatThrownBy(() -> tool.getWeatherByCity("北京"))
                .isInstanceOf(WeatherToolException.class).hasMessage("天气服务暂时不可用，请稍后重试");
    }

    @Test
    void translatesConnectionTimeout() {
        RetryableException exception = mock(RetryableException.class);
        when(client.getCurrentWeather("北京")).thenThrow(exception);
        assertThatThrownBy(() -> tool.getWeatherByCity("北京"))
                .isInstanceOf(WeatherToolException.class).hasMessage("天气服务暂时不可用，请稍后重试");
    }

    @Test
    void sanitizesUnexpectedFeignFailure() {
        FeignException exception = mock(FeignException.class);
        when(exception.status()).thenReturn(500);
        when(client.getCurrentWeather("北京")).thenThrow(exception);
        assertThatThrownBy(() -> tool.getWeatherByCity("北京"))
                .isInstanceOf(WeatherToolException.class).hasMessage("查询天气失败");
    }

    @Test
    void sanitizesUnexpectedResponseMappingFailure() {
        when(client.getCurrentWeather("北京")).thenReturn(null);
        assertThatThrownBy(() -> tool.getWeatherByCity("北京"))
                .isInstanceOf(WeatherToolException.class).hasMessage("查询天气失败");
    }
}
