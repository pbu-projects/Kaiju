package lol.pbu.kaiju.controller;
import static io.micronaut.scheduling.TaskExecutors.BLOCKING;
import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.AdministrativeRegion;
import lol.pbu.kaiju.repository.AdministrativeRegionRepository;
import lol.pbu.kaiju.util.ExistenceValidator;

import java.util.Optional;
import java.util.UUID;

@ExecuteOn(BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/administrative-regions")
public class AdministrativeRegionController implements ExistenceValidator {

    private final AdministrativeRegionRepository administrativeRegionRepository;

    public AdministrativeRegionController(AdministrativeRegionRepository administrativeRegionRepository) {
        this.administrativeRegionRepository = administrativeRegionRepository;
    }

    @Get
    public CursoredPage<AdministrativeRegion> getAdministrativeRegions(@Valid CursoredPageable pageable) {
        return administrativeRegionRepository.findAll(pageable);
    }

    @Get("/{id}")
    public Optional<AdministrativeRegion> getAdministrativeRegion(@PathVariable UUID id) {
        return administrativeRegionRepository.findById(id);
    }

    @Post
    public AdministrativeRegion addAdministrativeRegion(@Valid @Body AdministrativeRegion region) {
        return administrativeRegionRepository.save(region);
    }

    @Put("/{id}")
    public AdministrativeRegion updateAdministrativeRegion(@PathVariable UUID id, @Valid @Body AdministrativeRegion region) {
        checkExists(administrativeRegionRepository, id);
        return administrativeRegionRepository.update(region.withId(id));
    }

    @Delete("/{id}")
    public void deleteAdministrativeRegion(@PathVariable UUID id) {
        checkExists(administrativeRegionRepository, id);
        administrativeRegionRepository.deleteById(id);
    }
}
