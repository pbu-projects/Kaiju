package lol.pbu.kaiju.config;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;

@Factory
public class SpatialConfig {

    @Singleton
    public GeometryFactory geometryFactory() {
        return new GeometryFactory(new PrecisionModel(), 4326);
    }
}
