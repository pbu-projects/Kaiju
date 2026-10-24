package lol.pbu.kaiju.fixtures.builder

import lol.pbu.kaiju.domain.Tag
import lol.pbu.kaiju.repository.TagRepository

import java.util.UUID

class TagBuilder {

    private final TagRepository tagRepository

    private UUID id
    private String name

    TagBuilder(TagRepository tagRepository) {
        this.tagRepository = tagRepository
    }

    TagBuilder withName(String name) {
        this.name = name
        return this
    }

    TagBuilder withId(UUID id) {
        this.id = id
        return this
    }

    Tag build() {
        String tagName = name ?: "tag-${UUID.randomUUID().toString().substring(0, 8)}"
        return new Tag(id, tagName)
    }

    Tag persist() {
        assert tagRepository != null : "Cannot persist tag without a TagRepository"
        return tagRepository.save(build())
    }
}
