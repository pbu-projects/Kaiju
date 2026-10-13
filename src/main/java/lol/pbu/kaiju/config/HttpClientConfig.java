package lol.pbu.kaiju.config;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import java.net.http.HttpClient;
import java.time.Duration;

@Factory
public class HttpClientConfig {

    @Singleton
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
