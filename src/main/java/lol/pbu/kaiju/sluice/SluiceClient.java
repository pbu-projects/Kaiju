package lol.pbu.kaiju.sluice;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.client.annotation.Client;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Client("${kaiju.sluice.url:`http://localhost:8088`}")
public interface SluiceClient {

    @Get("/api/v1/boundaries/states")
    List<SluiceBoundaryDto> getStates();

    @Get("/api/v1/boundaries/states/{stateCode}")
    Optional<SluiceBoundaryDto> getState(@PathVariable String stateCode);

    @Get("/api/v1/boundaries/counties{?state}")
    List<SluiceBoundaryDto> getCounties(@Nullable @QueryValue String state);

    @Get("/api/v1/boundaries/cities{?state,county}")
    List<SluiceBoundaryDto> getCities(@Nullable @QueryValue String state, @Nullable @QueryValue String county);

    @Get("/api/v1/boundaries/resolve{?lat,lon}")
    Optional<SluiceReverseGeocodeResult> resolve(@QueryValue Double lat, @QueryValue Double lon);

    @Get("/api/v1/boundaries/health")
    Map<String, Object> health();
}
