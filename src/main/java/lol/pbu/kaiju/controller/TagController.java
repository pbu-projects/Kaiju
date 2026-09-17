package lol.pbu.kaiju.controller;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Tag;
import lol.pbu.kaiju.repository.TagRepository;

import java.util.Optional;
import java.util.UUID;
import static io.micronaut.http.HttpStatus.NOT_FOUND;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("isAuthenticated()")
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
        Tag existing = tagRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(NOT_FOUND, "Tag not found"));
        
        Tag securePayload = new Tag(
                id,
                tag.name()
        );
        return tagRepository.update(securePayload);
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
            throw new HttpStatusException(NOT_FOUND, "Tag not found");
        }
        tagRepository.deleteById(id);
    }

}
