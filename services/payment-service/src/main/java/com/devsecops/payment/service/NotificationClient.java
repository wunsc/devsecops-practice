// src/main/java/com/devsecops/payment/service/NotificationClient.java
// REST client for the .NET NotificationApi running in sampleapi-dev namespace.
package com.devsecops.payment.service;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * NotificationClient — sends payment confirmation notifications via the .NET NotificationApi.
 *
 * <p>This client crosses OpenShift namespace boundaries: PaymentService runs in
 * javaapp-dev while NotificationApi runs in sampleapi-dev. The Kubernetes DNS
 * name format is: {service}.{namespace}.svc:{port}
 *
 * <p>All calls are wrapped in try/catch to ensure payment processing succeeds
 * even if the notification service is unavailable. Payment confirmation is
 * considered a non-critical side effect — failing to notify should never
 * block or roll back a payment.
 *
 * <p>The OTel Java agent auto-instruments RestTemplate, so the outbound HTTP
 * call automatically creates a child span with the downstream service's trace
 * context propagated via W3C traceparent headers.
 */
@Service
public class NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);

    private final RestTemplate restTemplate;

    /** Base URL of the .NET NotificationApi (e.g., http://notificationapi.sampleapi-dev.svc:8081) */
    private final String notificationApiUrl;

    public NotificationClient(RestTemplate restTemplate,
                              @Value("${services.notificationapi.url}") String notificationApiUrl) {
        this.restTemplate = restTemplate;
        this.notificationApiUrl = notificationApiUrl;
    }

    /**
     * Sends a notification to the .NET NotificationApi.
     *
     * <p>POSTs to /api/Notify with a JSON body containing type, message, and recipient.
     * The .NET API handles routing to the appropriate notification channel (email, SMS, etc.).
     *
     * <p>Failure is gracefully handled — logs a warning but does not throw. This prevents
     * notification service outages from cascading into payment processing failures.
     *
     * @param type      notification type (e.g., "PAYMENT_CONFIRMATION", "PAYMENT_REFUND")
     * @param message   human-readable notification message
     * @param recipient the notification recipient (email, phone, or customer identifier)
     * @return true if the notification was sent successfully, false otherwise
     */
    @WithSpan("NotificationClient.sendNotification")
    public boolean sendNotification(String type, String message, String recipient) {
        String url = notificationApiUrl + "/api/Notify";
        log.info("Sending notification: type='{}', recipient='{}', url='{}'", type, recipient, url);

        try {
            // Build the notification request payload matching the .NET API contract
            Map<String, String> payload = new HashMap<>();
            payload.put("type", type);
            payload.put("message", message);
            payload.put("recipient", recipient);

            // POST to the .NET NotificationApi
            // RestTemplate is auto-instrumented by OTel — trace context propagates automatically
            restTemplate.postForObject(url, payload, String.class);

            log.info("Notification sent successfully: type='{}', recipient='{}'", type, recipient);
            return true;

        } catch (Exception e) {
            // Graceful failure — notification is a non-critical side effect
            // Log the error with enough context for troubleshooting, but do not re-throw
            log.warn("Failed to send notification to NotificationApi at '{}': type='{}', recipient='{}', error='{}'",
                    url, type, recipient, e.getMessage());
            return false;
        }
    }
}
