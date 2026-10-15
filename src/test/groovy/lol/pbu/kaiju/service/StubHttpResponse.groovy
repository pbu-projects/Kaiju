package lol.pbu.kaiju.service

import groovy.transform.CompileStatic
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import javax.net.ssl.SSLSession

@CompileStatic
class StubHttpResponse<T> implements HttpResponse<T> {
    int code
    T responseBody

    StubHttpResponse(int code, T responseBody) {
        this.code = code
        this.responseBody = responseBody
    }

    @Override
    int statusCode() {
        return code
    }

    @Override
    HttpRequest request() {
        return null
    }

    @Override
    Optional<HttpResponse<T>> previousResponse() {
        return Optional.empty()
    }

    @Override
    HttpHeaders headers() {
        return null
    }

    @Override
    T body() {
        return responseBody
    }

    @Override
    Optional<SSLSession> sslSession() {
        return Optional.empty()
    }

    @Override
    URI uri() {
        return null
    }

    @Override
    HttpClient.Version version() {
        return HttpClient.Version.HTTP_2
    }
}
