package lol.pbu.kaiju.service

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Collections
import java.util.List
import java.util.Map
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

class StubHttpClient extends HttpClient {
    Closure sendHandler
    Closure defaultSendHandler
    List<HttpRequest> recordedRequests = Collections.synchronizedList([])
    boolean validateRequestContract = false
    boolean validateResponseContract = false
    boolean defaultValidateRequestContract = false
    boolean defaultValidateResponseContract = false
    TigerwebContractValidator validator = new TigerwebContractValidator()

    StubHttpClient() {}

    StubHttpClient(Map params) {
        if (params.sendHandler) {
            this.sendHandler = params.sendHandler as Closure
            this.defaultSendHandler = params.defaultSendHandler ? (params.defaultSendHandler as Closure) : this.sendHandler
        }
        if (params.defaultSendHandler && !params.sendHandler) {
            this.defaultSendHandler = params.defaultSendHandler as Closure
            this.sendHandler = this.defaultSendHandler
        }
        if (params.validateRequestContract != null) {
            this.validateRequestContract = params.validateRequestContract as boolean
            this.defaultValidateRequestContract = this.validateRequestContract
        }
        if (params.validateResponseContract != null) {
            this.validateResponseContract = params.validateResponseContract as boolean
            this.defaultValidateResponseContract = this.validateResponseContract
        }
        if (params.validator) {
            this.validator = params.validator as TigerwebContractValidator
        }
    }

    void reset() {
        recordedRequests.clear()
        sendHandler = defaultSendHandler
        validateRequestContract = defaultValidateRequestContract
        validateResponseContract = defaultValidateResponseContract
    }

    HttpRequest getLastRequest() {
        recordedRequests.isEmpty() ? null : recordedRequests.last()
    }

    void validateRecordedRequests() {
        for (HttpRequest req : recordedRequests) {
            validator.validateRequest(req)
        }
    }

    @Override
    <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        recordedRequests.add(request)
        if (validateRequestContract) {
            validator.validateRequest(request)
        }
        if (sendHandler == null) {
            throw new IllegalStateException("sendHandler is not configured on StubHttpClient")
        }
        HttpResponse<T> response = sendHandler.call(request, responseBodyHandler) as HttpResponse<T>
        if (validateResponseContract && response != null) {
            response = (HttpResponse<T>) validator.validateResponse(response)
        }
        return response
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
