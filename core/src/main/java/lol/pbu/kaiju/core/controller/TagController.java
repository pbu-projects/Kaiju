package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.Tag;
import lol.pbu.kaiju.core.model.TagRequest;
import lol.pbu.kaiju.core.model.TagResponse;
import lol.pbu.kaiju.core.repository.TagRepository;

import java.util.Optional;
import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/tags")
public class TagController {

    private final TagRepository tagRepository;

    public TagController(TagRepository tagRepository) {
        this.tagRepository = tagRepository;
    }

    @Get
    public Page<TagResponse> list(@Valid Pageable pageable) {
        return tagRepository.findAll(pageable).map(this::toResponse);
    }

    @Get("/{id}")
    public Optional<TagResponse> get(@PathVariable UUID id) {
        return tagRepository.findById(id).map(this::toResponse);
    }

    @Post
    @Status(HttpStatus.CREATED)
    public TagResponse create(@Valid @Body TagRequest request) {
        Tag tag = new Tag(null, request.name());
        return toResponse(tagRepository.save(tag));
    }

    @Put("/{id}")
    public TagResponse update(@PathVariable UUID id, @Valid @Body TagRequest request) {
        Tag tag = new Tag(id, request.name());
        return toResponse(tagRepository.update(tag));
    }

    @Delete("/{id}")
    @Status(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        tagRepository.deleteById(id);
    }

    private TagResponse toResponse(Tag tag) {
        return new TagResponse(tag.id(), tag.name());
    }
}
