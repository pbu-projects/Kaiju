package lol.pbu.kaiju.core.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.core.domain.Tag;
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
    public CursoredPage<Tag> getTags(@Valid CursoredPageable pageable) {
        return tagRepository.findAll(pageable);
    }

    @Get("/{id}")
    public Optional<Tag> getTag(@PathVariable UUID id) {
        return tagRepository.findById(id);
    }

    @Post
    public Tag addTag(@Valid @Body Tag tag) {
        return tagRepository.save(tag);
    }

    @Put("/{id}")
    public Tag updateTag(@PathVariable UUID id, @Valid @Body Tag tag) {
        if (!tagRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Tag not found");
        }
        return tagRepository.update(tag.withId(id));
    }

    @Delete("/{id}")
    public void deleteTag(@PathVariable UUID id) {
        tagRepository.deleteById(id);
    }
}
