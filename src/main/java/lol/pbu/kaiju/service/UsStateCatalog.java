package lol.pbu.kaiju.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catalog of all 50 US states, the District of Columbia, and US territories
 * with their USPS codes, 2-digit Census FIPS codes, and place layer rules.
 */
public final class UsStateCatalog {

    public record UsStateEntry(
            String stateCode,
            String fipsCode,
            String name,
            boolean cdpOnlyForPlaces
    ) {
        public UsStateEntry(String stateCode, String fipsCode, String name) {
            this(stateCode, fipsCode, name, false);
        }
    }

    private static final List<UsStateEntry> ENTRIES = List.of(
            new UsStateEntry("AL", "01", "Alabama"),
            new UsStateEntry("AK", "02", "Alaska"),
            new UsStateEntry("AZ", "04", "Arizona"),
            new UsStateEntry("AR", "05", "Arkansas"),
            new UsStateEntry("CA", "06", "California"),
            new UsStateEntry("CO", "08", "Colorado"),
            new UsStateEntry("CT", "09", "Connecticut"),
            new UsStateEntry("DE", "10", "Delaware"),
            new UsStateEntry("DC", "11", "District of Columbia"),
            new UsStateEntry("FL", "12", "Florida"),
            new UsStateEntry("GA", "13", "Georgia"),
            new UsStateEntry("HI", "15", "Hawaii", true),
            new UsStateEntry("ID", "16", "Idaho"),
            new UsStateEntry("IL", "17", "Illinois"),
            new UsStateEntry("IN", "18", "Indiana"),
            new UsStateEntry("IA", "19", "Iowa"),
            new UsStateEntry("KS", "20", "Kansas"),
            new UsStateEntry("KY", "21", "Kentucky"),
            new UsStateEntry("LA", "22", "Louisiana"),
            new UsStateEntry("ME", "23", "Maine"),
            new UsStateEntry("MD", "24", "Maryland"),
            new UsStateEntry("MA", "25", "Massachusetts"),
            new UsStateEntry("MI", "26", "Michigan"),
            new UsStateEntry("MN", "27", "Minnesota"),
            new UsStateEntry("MS", "28", "Mississippi"),
            new UsStateEntry("MO", "29", "Missouri"),
            new UsStateEntry("MT", "30", "Montana"),
            new UsStateEntry("NE", "31", "Nebraska"),
            new UsStateEntry("NV", "32", "Nevada"),
            new UsStateEntry("NH", "33", "New Hampshire"),
            new UsStateEntry("NJ", "34", "New Jersey"),
            new UsStateEntry("NM", "35", "New Mexico"),
            new UsStateEntry("NY", "36", "New York"),
            new UsStateEntry("NC", "37", "North Carolina"),
            new UsStateEntry("ND", "38", "North Dakota"),
            new UsStateEntry("OH", "39", "Ohio"),
            new UsStateEntry("OK", "40", "Oklahoma"),
            new UsStateEntry("OR", "41", "Oregon"),
            new UsStateEntry("PA", "42", "Pennsylvania"),
            new UsStateEntry("RI", "44", "Rhode Island"),
            new UsStateEntry("SC", "45", "South Carolina"),
            new UsStateEntry("SD", "46", "South Dakota"),
            new UsStateEntry("TN", "47", "Tennessee"),
            new UsStateEntry("TX", "48", "Texas"),
            new UsStateEntry("UT", "49", "Utah"),
            new UsStateEntry("VT", "50", "Vermont"),
            new UsStateEntry("VA", "51", "Virginia"),
            new UsStateEntry("WA", "53", "Washington"),
            new UsStateEntry("WV", "54", "West Virginia"),
            new UsStateEntry("WI", "55", "Wisconsin"),
            new UsStateEntry("WY", "56", "Wyoming"),
            new UsStateEntry("AS", "60", "American Samoa"),
            new UsStateEntry("GU", "66", "Guam"),
            new UsStateEntry("MP", "69", "Northern Mariana Islands"),
            new UsStateEntry("PR", "72", "Puerto Rico"),
            new UsStateEntry("VI", "78", "U.S. Virgin Islands")
    );

    private static final Map<String, UsStateEntry> BY_STATE_CODE = ENTRIES.stream()
            .collect(Collectors.toUnmodifiableMap(UsStateEntry::stateCode, Function.identity()));

    private static final Map<String, UsStateEntry> BY_FIPS_CODE = ENTRIES.stream()
            .collect(Collectors.toUnmodifiableMap(UsStateEntry::fipsCode, Function.identity()));

    private UsStateCatalog() {
    }

    public static Optional<UsStateEntry> findByStateCode(String stateCode) {
        if (stateCode == null || stateCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_STATE_CODE.get(stateCode.trim().toUpperCase(Locale.ROOT)));
    }

    public static Optional<UsStateEntry> findByFipsCode(String fipsCode) {
        if (fipsCode == null || fipsCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_FIPS_CODE.get(fipsCode.trim()));
    }

    public static List<UsStateEntry> all() {
        return ENTRIES;
    }
}
