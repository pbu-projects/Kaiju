package lol.pbu.kaiju.sluice;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Singleton
public class SluiceService {

    private static final Logger LOG = LoggerFactory.getLogger(SluiceService.class);

    private final SluiceClient sluiceClient;
    private final boolean enabled;

    public SluiceService(
            SluiceClient sluiceClient,
            @Value("${kaiju.sluice.enabled:true}") boolean enabled
    ) {
        this.sluiceClient = sluiceClient;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAvailable() {
        if (!enabled) {
            return false;
        }
        try {
            Map<String, Object> health = sluiceClient.health();
            return health != null && "UP".equalsIgnoreCase(String.valueOf(health.get("status")));
        } catch (Exception e) {
            LOG.warn("Failed connecting to Sluice boundary service: {}", e.getMessage());
            return false;
        }
    }

    public Optional<SluiceBoundaryDto> findStateBoundary(String stateCode) {
        if (!enabled || stateCode == null || stateCode.isBlank()) {
            return Optional.empty();
        }
        try {
            return sluiceClient.getState(stateCode.trim());
        } catch (Exception e) {
            LOG.warn("Error fetching state boundary for '{}': {}", stateCode, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<SluiceReverseGeocodeResult> resolveCoordinates(double lat, double lon) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            return sluiceClient.resolve(lat, lon);
        } catch (Exception e) {
            LOG.warn("Error resolving coordinates ({}, {}): {}", lat, lon, e.getMessage());
            return Optional.empty();
        }
    }

    public List<SluiceBoundaryDto> getStates() {
        if (!enabled) {
            return Collections.emptyList();
        }
        try {
            List<SluiceBoundaryDto> states = sluiceClient.getStates();
            return states != null ? states : Collections.emptyList();
        } catch (Exception e) {
            LOG.warn("Error fetching state list from Sluice: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<SluiceBoundaryDto> getCounties(@Nullable String state) {
        if (!enabled) {
            return Collections.emptyList();
        }
        try {
            List<SluiceBoundaryDto> counties = sluiceClient.getCounties(state);
            return counties != null ? counties : Collections.emptyList();
        } catch (Exception e) {
            LOG.warn("Error fetching counties for state '{}' from Sluice: {}", state, e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<SluiceBoundaryDto> getCities(@Nullable String state, @Nullable String county) {
        if (!enabled) {
            return Collections.emptyList();
        }
        try {
            List<SluiceBoundaryDto> cities = sluiceClient.getCities(state, county);
            return cities != null ? cities : Collections.emptyList();
        } catch (Exception e) {
            LOG.warn("Error fetching cities for state '{}' and county '{}' from Sluice: {}", state, county, e.getMessage());
            return Collections.emptyList();
        }
    }
}
