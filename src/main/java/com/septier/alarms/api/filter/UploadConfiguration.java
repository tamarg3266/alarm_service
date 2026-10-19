package com.septier.alarms.api.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.*;

@Configuration
class UploadConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> uploadTimeout(@Value("${alarm.upload-timeout-ms}") int timeout) {
        if (timeout < 100 || timeout > 60000) throw new IllegalArgumentException("Upload timeout must be 100..60000 ms");
        return factory -> factory.addConnectorCustomizers(connector -> {
            connector.setProperty("disableUploadTimeout", "false");
            connector.setProperty("connectionUploadTimeout", Integer.toString(timeout));
            connector.setProperty("connectionTimeout", Integer.toString(timeout));
        });
    }
}
