package lol.pbu.kaiju.config;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import org.reactivestreams.Publisher;

@Filter(Filter.MATCH_ALL_PATTERN)
public class SecurityHeadersFilter implements HttpServerFilter {

    private static final String HEADER_X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    private static final String HEADER_X_FRAME_OPTIONS = "X-Frame-Options";
    private static final String HEADER_REFERRER_POLICY = "Referrer-Policy";
    private static final String HEADER_CONTENT_SECURITY_POLICY = "Content-Security-Policy";

    private static final String VALUE_NOSNIFF = "nosniff";
    private static final String VALUE_DENY = "DENY";
    private static final String VALUE_REFERRER_POLICY = "strict-origin-when-cross-origin";
    private static final String VALUE_CSP = "default-src 'self'; "
            + "script-src 'self' 'unsafe-inline' https://unpkg.com; "
            + "style-src 'self' 'unsafe-inline' https://unpkg.com https://fonts.googleapis.com; "
            + "font-src 'self' https://fonts.gstatic.com; "
            + "img-src 'self' data: https://*.tile.openstreetmap.org; "
            + "connect-src 'self' https://nominatim.openstreetmap.org; "
            + "frame-ancestors 'none'; "
            + "base-uri 'self'; "
            + "form-action 'self'";

    @Override
    public int getOrder() {
        return ServerFilterPhase.FIRST.order();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        return Publishers.map(chain.proceed(request), response -> {
            MutableHttpHeaders headers = response.getHeaders();
            headers.set(HEADER_X_CONTENT_TYPE_OPTIONS, VALUE_NOSNIFF);
            headers.set(HEADER_X_FRAME_OPTIONS, VALUE_DENY);
            headers.set(HEADER_REFERRER_POLICY, VALUE_REFERRER_POLICY);
            headers.set(HEADER_CONTENT_SECURITY_POLICY, VALUE_CSP);
            return response;
        });
    }
}
