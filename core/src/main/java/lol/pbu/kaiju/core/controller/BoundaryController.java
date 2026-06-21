package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.Boundary;
import lol.pbu.kaiju.core.model.BoundaryRequest;
import lol.pbu.kaiju.core.model.BoundaryResponse;
import lol.pbu.kaiju.core.repository.BoundaryRepository;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;

import java.util.Optional;
import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/boundaries")
public class BoundaryController {

    private final BoundaryRepository boundaryRepository;
    private final WKTReader wktReader = new WKTReader();
    private final WKTWriter wktWriter = new WKTWriter();
    private final WKBWriter wkbWriter = new WKBWriter(2, true);
    private final WKBReader wkbReader = new WKBReader();

    public BoundaryController(BoundaryRepository boundaryRepository) {
        this.boundaryRepository = boundaryRepository;
    }

    @Get
    public Page<BoundaryResponse> list(@Valid Pageable pageable) {
        return boundaryRepository.findAll(pageable).map(this::toResponse);
    }

    @Get("/{id}")
    public Optional<BoundaryResponse> get(@PathVariable UUID id) {
        return boundaryRepository.findById(id).map(this::toResponse);
    }

    @Post
    @Status(HttpStatus.CREATED)
    public BoundaryResponse create(@Valid @Body BoundaryRequest request) {
        Boundary boundary = new Boundary(
                null,
                request.name(),
                request.wkt() != null ? parseWktToWkb(request.wkt()) : null
        );
        return toResponse(boundaryRepository.save(boundary));
    }

    @Put("/{id}")
    public BoundaryResponse update(@PathVariable UUID id, @Valid @Body BoundaryRequest request) {
        Boundary boundary = new Boundary(
                id,
                request.name(),
                request.wkt() != null ? parseWktToWkb(request.wkt()) : null
        );
        return toResponse(boundaryRepository.update(boundary));
    }

    @Delete("/{id}")
    @Status(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        boundaryRepository.deleteById(id);
    }

    private byte[] parseWktToWkb(String wkt) {
        try {
            Polygon polygon = (Polygon) wktReader.read(wkt);
            polygon.setSRID(4326);
            return wkbWriter.write(polygon);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid WKT: " + e.getMessage());
        }
    }

    private BoundaryResponse toResponse(Boundary boundary) {
        String wkt = null;
        if (boundary.geom() != null) {
            try {
                Polygon polygon = (Polygon) wkbReader.read(boundary.geom());
                wkt = wktWriter.write(polygon);
            } catch (Exception ignored) {}
        }
        return new BoundaryResponse(boundary.id(), boundary.name(), wkt);
    }
}
