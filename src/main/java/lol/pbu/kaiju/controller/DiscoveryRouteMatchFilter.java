package lol.pbu.kaiju.controller;

import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRouteMatch;
import io.micronaut.web.router.filter.FilteredRouter;
import io.micronaut.web.router.filter.RouteMatchFilter;
import jakarta.inject.Singleton;

import java.util.function.Predicate;

@Singleton
public class DiscoveryRouteMatchFilter implements RouteMatchFilter, BeanCreatedEventListener<Router> {

    @Override
    public Router onCreated(BeanCreatedEvent<Router> event) {
        return new FilteredRouter(event.getBean(), this);
    }

    @Override
    public <T, R> Predicate<UriRouteMatch<T, R>> filter(HttpRequest<?> request) {
        if (request.getMethod() != HttpMethod.GET || !"/projects".equals(request.getPath())) {
            return routeMatch -> true;
        }
        boolean isHtmx = "true".equalsIgnoreCase(request.getHeaders().get("HX-Request"));
        boolean acceptsHtml = isHtmx || request.getHeaders().accept().stream()
                .anyMatch(mediaType -> MediaType.TEXT_HTML.equals(mediaType.getName()));
        return routeMatch -> acceptsHtml == DiscoveryViewController.class.equals(routeMatch.getDeclaringType());
    }
}
