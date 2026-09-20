package lol.pbu.kaiju.controller;
import static lol.pbu.kaiju.security.SecurityRoles.IS_AUTHENTICATED;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Tag;
import lol.pbu.kaiju.repository.TagRepository;

import java.util.Optional;
import java.util.UUID;

@ExecuteOn(TaskExecutors.BLOCKING)
@Secured(IS_AUTHENTICATED)
@Controller("/tags")
public class TagController implements ExistenceValidator {

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
     *
     * @param id  the ID of the tag to update
     * @param tag the updated tag details
     * @return the updated tag
     */
    @Put("/{id}")
    public Tag updateTag(@PathVariable UUID id, @Valid @Body Tag tag) {
        checkExists(tagRepository, id);
        
        Tag securePayload = new Tag(
                id,
                tag.name()
        );
        return tagRepository.update(securePayload);
    }


    /**
     * Deletes a tag by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the tag does not exist.
     *
     * @param id the ID of the tag to delete
     */
    @Delete("/{id}")
    public void deleteTag(@PathVariable UUID id) {
        checkExists(tagRepository, id);
        tagRepository.deleteById(id);
    }

}
