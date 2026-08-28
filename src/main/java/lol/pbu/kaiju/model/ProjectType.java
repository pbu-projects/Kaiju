package lol.pbu.kaiju.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum ProjectType {
    STANDARD,
    OPEN_DOOR,
    REGIONAL
}
