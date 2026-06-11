// order-service/src/main/java/com/devsecops/order/OrderServiceApplication.java
// Main entry point for the OrderService Spring Boot application.
// The OTel Java agent (-javaagent:opentelemetry-javaagent.jar) is attached at JVM startup,
// so no programmatic OTel SDK setup is needed here.
package com.devsecops.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
