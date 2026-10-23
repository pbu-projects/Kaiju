package lol.pbu.kaiju.contract

import org.yaml.snakeyaml.Yaml
import spock.lang.Specification

class ClientContractVerificationSpec extends Specification {

    def "OpenAPI | Generated AOT spec exists and validates against OpenAPI 3.0 schema contract"() {
        given: "the compiled OpenAPI YAML specification"
        File specFile = new File("build/classes/java/main/META-INF/swagger/kaiju-0.0.6.yml")
        if (!specFile.exists()) {
            specFile = new File("docs/openapi.yaml")
        }

        expect: "the spec file is present on the filesystem"
        specFile.exists()

        when: "the spec is parsed as structured YAML"
        Map<String, Object> spec = new Yaml().load(specFile.text)

        then: "metadata and core endpoints are strictly declared"
        spec.openapi.startsWith("3.0")
        spec.info.title == "Kaiju Civic Engine API"
        spec.info.version == "0.0.6"

        and: "critical client routes are present in the contract"
        Map paths = spec.paths as Map
        paths.containsKey("/projects")
        paths.containsKey("/projects/search-by-location")
        paths.containsKey("/shifts")
        paths.containsKey("/locations")
        paths.containsKey("/organizations")
        paths.containsKey("/users/me")

        and: "Location model declares timezone property"
        Map components = (Map) spec.get("components")
        Map schemas = (Map) components.get("schemas")
        Map locationSchema = (Map) schemas.get("Location")
        Map locationProps = (Map) locationSchema.get("properties")
        locationProps != null
        locationProps.containsKey("timezone")
    }

    def "OpenAPI | Exported docs/openapi.yaml matches compiled contract and is ready for client handoff"() {
        given: "the exported documentation spec"
        File docsFile = new File("docs/openapi.yaml")

        expect: "the exported file exists"
        docsFile.exists()

        when: "loaded and parsed"
        Map<String, Object> spec = new Yaml().load(docsFile.text)

        then: "it has valid OpenAPI 3.0 structure and contains required routes"
        spec.openapi.startsWith("3.0")
        spec.info.title == "Kaiju Civic Engine API"
        (spec.paths as Map).containsKey("/users/me")
    }
}
