package lol.pbu.kaiju.service

import groovy.transform.CompileStatic
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.ArrayList
import java.util.Collections
import java.util.List
import java.util.Map
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

@CompileStatic
class StubHttpClient extends HttpClient {
    private final AtomicReference<Closure<?>> sendHandler = new AtomicReference<Closure<?>>(null)
    private final AtomicReference<Closure<?>> defaultSendHandler = new AtomicReference<Closure<?>>(null)
    final List<HttpRequest> recordedRequests = Collections.synchronizedList(new ArrayList<HttpRequest>())
    volatile boolean validateRequestContract = false
    volatile boolean validateResponseContract = false
    volatile boolean defaultValidateRequestContract = false
    volatile boolean defaultValidateResponseContract = false
    volatile TigerwebContractValidator validator = new TigerwebContractValidator()

    StubHttpClient() {}

    StubHttpClient(Map<?, ?> params) {
        if (params != null) {
            if (params.containsKey("sendHandler") && params.get("sendHandler") != null) {
                Closure<?> handler = (Closure<?>) params.get("sendHandler")
                this.sendHandler.set(handler)
                if (params.containsKey("defaultSendHandler") && params.get("defaultSendHandler") != null) {
                    this.defaultSendHandler.set((Closure<?>) params.get("defaultSendHandler"))
                } else {
                    this.defaultSendHandler.set(handler)
                }
            } else if (params.containsKey("defaultSendHandler") && params.get("defaultSendHandler") != null) {
                Closure<?> defHandler = (Closure<?>) params.get("defaultSendHandler")
                this.defaultSendHandler.set(defHandler)
                this.sendHandler.set(defHandler)
            }

            if (params.containsKey("validateRequestContract") && params.get("validateRequestContract") != null) {
                this.validateRequestContract = Boolean.parseBoolean(params.get("validateRequestContract").toString())
                this.defaultValidateRequestContract = this.validateRequestContract
            }
            if (params.containsKey("validateResponseContract") && params.get("validateResponseContract") != null) {
                this.validateResponseContract = Boolean.parseBoolean(params.get("validateResponseContract").toString())
                this.defaultValidateResponseContract = this.validateResponseContract
            }
            if (params.containsKey("validator") && params.get("validator") != null) {
                this.validator = (TigerwebContractValidator) params.get("validator")
            }
        }
    }

    Closure<?> getSendHandler() {
        return sendHandler.get()
    }

    void setSendHandler(Closure<?> handler) {
        sendHandler.set(handler)
    }

    Closure<?> getDefaultSendHandler() {
        return defaultSendHandler.get()
    }

    void setDefaultSendHandler(Closure<?> handler) {
        defaultSendHandler.set(handler)
    }

    void reset() {
        recordedRequests.clear()
        sendHandler.set(defaultSendHandler.get())
        validateRequestContract = defaultValidateRequestContract
        validateResponseContract = defaultValidateResponseContract
    }

    HttpRequest getLastRequest() {
        synchronized (recordedRequests) {
            return recordedRequests.isEmpty() ? null : recordedRequests.get(recordedRequests.size() - 1)
        }
    }

    void validateRecordedRequests() {
        synchronized (recordedRequests) {
            for (HttpRequest req : recordedRequests) {
                validator.validateRequest(req)
            }
        }
    }

    @Override
    <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        recordedRequests.add(request)
        if (validateRequestContract) {
            validator.validateRequest(request)
        }
        Closure<?> handler = sendHandler.get()
        if (handler == null) {
            throw new IllegalStateException("sendHandler is not configured on StubHttpClient")
        }
        @SuppressWarnings("unchecked")
        HttpResponse<T> response = (HttpResponse<T>) handler.call(request, responseBodyHandler)
        if (validateResponseContract && response != null) {
            response = validator.validateResponse(response)
        }
        return response
    }

    @Override
    Optional<CookieHandler> cookieHandler() {
        return Optional.empty()
    }

    @Override
    Optional<Duration> connectTimeout() {
        return Optional.empty()
    }

    @Override
    HttpClient.Redirect followRedirects() {
        return HttpClient.Redirect.NEVER
    }

    @Override
    Optional<ProxySelector> proxy() {
        return Optional.empty()
    }

    @Override
    SSLContext sslContext() {
        return null
    }

    @Override
    SSLParameters sslParameters() {
        return null
    }

    @Override
    Optional<Authenticator> authenticator() {
        return Optional.empty()
    }

    @Override
    HttpClient.Version version() {
        return HttpClient.Version.HTTP_2
    }

    @Override
    Optional<Executor> executor() {
        return Optional.empty()
    }

    @Override
    <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        return null
    }

    @Override
    <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return null
    }
}
