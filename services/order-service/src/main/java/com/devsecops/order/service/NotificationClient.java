// order-service/src/main/java/com/devsecops/order/service/NotificationClient.java
// HTTP client for the .NET NotificationApi service.
// Sends notifications (order confirmation, status updates) via the .NET service.
// This is another cross-language call that appears in distributed traces.
// Graceful failure: notification failures don't affect order processing.
package com.devsecops.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);

    private final RestTemplate restTemplate;
    private final String sampleApiUrl;

    public NotificationClient(
            RestTemplate restTemplate,
            @Value("${services.sampleapi.url}") String sampleApiUrl) {
        this.restTemplate = restTemplate;
        this.sampleApiUrl = sampleApiUrl;
    }

    /**
     * Send a notification via the .NET NotificationApi.
     * Calls POST /api/Notify with type, message, and recipient.
     *
     * @param type      notification type (e.g., "ORDER_CREATED", "ORDER_SHIPPED")
     * @param message   the notification message body
     * @param recipient the notification recipient (email, user ID, etc.)
     */
    public void sendNotification(String type, String message, String recipient) {
        String url = sampleApiUrl + "/api/Notify";
        log.info("Sending notification: type={}, recipient={}, url={}", type, recipient, url);

        try {
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("type", type);
            requestBody.put("message", message);
            requestBody.put("recipient", recipient);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);

            restTemplate.postForObject(url, request, String.class);
            log.info("Notification sent successfully: type={}, recipient={}", type, recipient);
        } catch (RestClientException e) {
            // Notification failures are non-critical — log and continue
            log.error("Failed to send notification: type={}, recipient={}, error={}",
                    type, recipient, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error sending notification: type={}, error={}", type, e.getMessage());
        }
    }
}
