package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.AdministrativeRegion;
import lol.pbu.kaiju.core.repository.AdministrativeRegionRepository;

import java.util.Optional;
import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/administrative-regions")
public class AdministrativeRegionController {

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
        if (!administrativeRegionRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Administrative region not found");
        }
        return administrativeRegionRepository.update(region.withId(id));
    }

    @Put("/{id}/no-look")
    public AdministrativeRegion updateAdministrativeRegionNoLook(@PathVariable UUID id, @Valid @Body AdministrativeRegion region) {
        return administrativeRegionRepository.update(region.withId(id));
    }

    @Delete("/{id}")
    public void deleteAdministrativeRegion(@PathVariable UUID id) {
        if (!administrativeRegionRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Administrative region not found");
        }
        administrativeRegionRepository.deleteById(id);
    }

    @Delete("/{id}/no-look")
    public void deleteAdministrativeRegionNoLook(@PathVariable UUID id) {
        administrativeRegionRepository.deleteById(id);
    }
}
