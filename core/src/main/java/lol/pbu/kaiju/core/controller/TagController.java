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

    /**
     * Updates an existing tag by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the tag does not exist.
     * Refer to the sister method {@link #updateTagNoLook(UUID, Tag)} to update without validation.
     *
     * @param id  the ID of the tag to update
     * @param tag the updated tag details
     * @return the updated tag
     */
    @Put("/{id}")
    public Tag updateTag(@PathVariable UUID id, @Valid @Body Tag tag) {
        if (!tagRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Tag not found");
        }
        return tagRepository.update(tag.withId(id));
    }

    /**
     * Updates a tag by its ID without checking if it exists first.
     * This method is provided because standard repositories do not throw an error if the ID does not already exist.
     * Refer to the sister method {@link #updateTag(UUID, Tag)} to update with existence validation.
     *
     * @param id  the ID of the tag to update
     * @param tag the updated tag details
     * @return the updated tag
     */
    @Put("/{id}/no-look")
    public Tag updateTagNoLook(@PathVariable UUID id, @Valid @Body Tag tag) {
        return tagRepository.update(tag.withId(id));
    }

    /**
     * Deletes a tag by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the tag does not exist.
     * Refer to the sister method {@link #deleteTagNoLook(UUID)} to delete without validation.
     *
     * @param id the ID of the tag to delete
     */
    @Delete("/{id}")
    public void deleteTag(@PathVariable UUID id) {
        if (!tagRepository.existsById(id)) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Tag not found");
        }
        tagRepository.deleteById(id);
    }

    /**
     * Deletes a tag by its ID without checking if it exists first.
     * This method is provided because standard repositories do not throw an error if the ID does not already exist.
     * Refer to the sister method {@link #deleteTag(UUID)} to delete with existence validation.
     *
     * @param id the ID of the tag to delete
     */
    @Delete("/{id}/no-look")
    public void deleteTagNoLook(@PathVariable UUID id) {
        tagRepository.deleteById(id);
    }
}
