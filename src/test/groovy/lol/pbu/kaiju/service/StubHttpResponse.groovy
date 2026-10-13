package lol.pbu.kaiju.service

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import javax.net.ssl.SSLSession

class StubHttpResponse<T> implements HttpResponse<T> {
    int code
    T responseBody

    StubHttpResponse(int code, T responseBody) {
        this.code = code
        this.responseBody = responseBody
    }

    @Override
    int statusCode() { code }

    @Override
    HttpRequest request() { null }

    @Override
    Optional<HttpResponse<T>> previousResponse() { Optional.empty() }

    @Override
    HttpHeaders headers() { null }

    @Override
    T body() { responseBody }

    @Override
    Optional<SSLSession> sslSession() { Optional.empty() }

    @Override
    URI uri() { null }

    @Override
    HttpClient.Version version() { HttpClient.Version.HTTP_2 }
}
