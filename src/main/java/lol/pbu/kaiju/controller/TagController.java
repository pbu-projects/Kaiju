package lol.pbu.kaiju.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.CursoredPageable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.validation.Valid;
import lol.pbu.kaiju.domain.Tag;
import lol.pbu.kaiju.dto.CreateTagCommand;
import lol.pbu.kaiju.dto.UpdateTagCommand;
import lol.pbu.kaiju.repository.TagRepository;
import lol.pbu.kaiju.util.PageableUtils;

import java.util.Optional;
import java.util.UUID;

import static io.micronaut.security.rules.SecurityRule.IS_AUTHENTICATED;

@ExecuteOn(TaskExecutors.VIRTUAL)
@Secured(IS_AUTHENTICATED)
@Controller("/tags")
public class TagController {

    public static final String DEFAULT_SORT_FIELD = "name";

    private final TagRepository tagRepository;

    public TagController(TagRepository tagRepository) {
        this.tagRepository = tagRepository;
    }

    @Get
    public CursoredPage<Tag> getTags(@Nullable @Valid CursoredPageable pageable) {
        return tagRepository.findAll(PageableUtils.resolvePageable(pageable, DEFAULT_SORT_FIELD));
    }

    @Get("/{id}")
    public Optional<Tag> getTag(@PathVariable UUID id) {
        return tagRepository.findById(id);
    }

    @Post
    public Tag addTag(@Valid @Body CreateTagCommand command) {
        return tagRepository.save(new Tag(null, command.name()));
    }

    /**
     * Updates an existing tag by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the tag does not exist.
     *
     * @param id      the ID of the tag to update
     * @param command the updated tag details
     * @return the updated tag
     */
    @Put("/{id}")
    public Tag updateTag(@PathVariable UUID id, @Valid @Body UpdateTagCommand command) {
        tagRepository.findById(id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Tag not found"));
        return tagRepository.update(new Tag(id, command.name()));
    }

    /**
     * Deletes a tag by its ID after validating that it exists.
     * Throws 404 NOT_FOUND if the tag does not exist.
     *
     * @param id the ID of the tag to delete
     */
    @Delete("/{id}")
    public void deleteTag(@PathVariable UUID id) {
        long deletedCount = tagRepository.removeById(id);
        if (deletedCount == 0) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Tag not found");
        }
    }
}
