package lol.pbu.kaiju.service

import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

class StubHttpClient extends HttpClient {
    Closure sendHandler

    @Override
    <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        return sendHandler.call(request, responseBodyHandler) as HttpResponse<T>
    }

    @Override
    Optional<CookieHandler> cookieHandler() { Optional.empty() }

    @Override
    Optional<Duration> connectTimeout() { Optional.empty() }

    @Override
    HttpClient.Redirect followRedirects() { HttpClient.Redirect.NEVER }

    @Override
    Optional<ProxySelector> proxy() { Optional.empty() }

    @Override
    SSLContext sslContext() { null }

    @Override
    SSLParameters sslParameters() { null }

    @Override
    Optional<Authenticator> authenticator() { Optional.empty() }

    @Override
    HttpClient.Version version() { HttpClient.Version.HTTP_2 }

    @Override
    Optional<Executor> executor() { Optional.empty() }

    @Override
    <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) { null }

    @Override
    <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { null }
}
