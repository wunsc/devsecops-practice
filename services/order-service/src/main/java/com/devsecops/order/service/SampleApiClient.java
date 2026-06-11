// order-service/src/main/java/com/devsecops/order/service/SampleApiClient.java
// HTTP client for the SampleApi (.NET microservice).
// Calls the weather forecast endpoint and uses temperature data to generate
// a shipping estimate string. This is a cross-language call (Java → .NET) that
// demonstrates distributed tracing across heterogeneous services.
package com.devsecops.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class SampleApiClient {

    private static final Logger log = LoggerFactory.getLogger(SampleApiClient.class);

    private final RestTemplate restTemplate;
    private final String sampleApiUrl;

    public SampleApiClient(
            RestTemplate restTemplate,
            @Value("${services.sampleapi.url}") String sampleApiUrl) {
        this.restTemplate = restTemplate;
        this.sampleApiUrl = sampleApiUrl;
    }

    /**
     * Get weather forecast data for shipping estimate calculation.
     * Calls GET /api/WeatherForecast on the .NET SampleApi.
     *
     * The returned temperature is used to estimate shipping conditions:
     * - Extreme cold (< 0C) or heat (> 35C): "4-5 days (adverse weather)"
     * - Moderate (0-35C): "2-3 days (clear weather)"
     *
     * @param city the city name (used for logging; the .NET API returns general forecast)
     * @return a human-readable shipping estimate string
     */
    @SuppressWarnings("unchecked")
    public String getWeatherForCity(String city) {
        String url = sampleApiUrl + "/api/WeatherForecast";
        log.info("Fetching weather for shipping estimate: city={}, url={}", city, url);

        try {
            // The .NET WeatherForecast API returns a JSON array of forecast objects
            List<Map<String, Object>> forecasts = restTemplate.getForObject(url, List.class);

            if (forecasts != null && !forecasts.isEmpty()) {
                // Use the first forecast entry's temperature
                Map<String, Object> firstForecast = forecasts.get(0);
                int temperatureC = 20; // safe default

                Object tempObj = firstForecast.get("temperatureC");
                if (tempObj != null) {
                    temperatureC = Integer.parseInt(tempObj.toString());
                }

                String summary = (String) firstForecast.getOrDefault("summary", "Unknown");
                log.info("Weather data for {}: {}C, {}", city, temperatureC, summary);

                // Determine shipping estimate based on temperature
                return calculateShippingEstimate(temperatureC, summary);
            }

            log.warn("Empty weather response for city={}", city);
            return "3-4 days (no weather data)";
        } catch (RestClientException e) {
            log.error("Failed to fetch weather for city={}: {}. Using default estimate.", city, e.getMessage());
            return "3-5 days (weather service unavailable)";
        } catch (Exception e) {
            log.error("Unexpected error processing weather for city={}: {}", city, e.getMessage());
            return "3-5 days (estimate unavailable)";
        }
    }

    /**
     * Calculate a shipping estimate string based on weather conditions.
     * Extreme temperatures slow down shipping due to special handling requirements.
     */
    private String calculateShippingEstimate(int temperatureC, String summary) {
        if (temperatureC < 0) {
            return "4-5 days (freezing conditions: " + temperatureC + "C, " + summary + ")";
        } else if (temperatureC > 35) {
            return "4-5 days (extreme heat: " + temperatureC + "C, " + summary + ")";
        } else if (temperatureC > 25) {
            return "2-3 days (warm weather: " + temperatureC + "C, " + summary + ")";
        } else {
            return "2-3 days (clear weather: " + temperatureC + "C, " + summary + ")";
        }
    }
}
