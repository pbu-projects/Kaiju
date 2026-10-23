package lol.pbu.kaiju.client

import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.annotation.ClientFilter
import io.micronaut.http.annotation.RequestFilter

@ClientFilter
class ClientAuthFilter {

    static String token

    @RequestFilter
    void filter(MutableHttpRequest<?> request) {
        if (token != null) {
            request.bearerAuth(token)
        }
    }
}
