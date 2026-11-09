package lol.pbu.kaiju.sluice;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum SluiceBoundaryLevel {
    STATE,
    COUNTY,
    CITY
}
