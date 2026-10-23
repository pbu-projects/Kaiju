package lol.pbu;

import io.micronaut.runtime.Micronaut;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;

@OpenAPIDefinition(
        info = @Info(
                title = "Kaiju Civic Engine API",
                version = "0.0.5",
                description = "High-performance spatial volunteer management and civic engagement engine",
                contact = @Contact(name = "Peanut Butter Unicorn", url = "https://kaiju.pbu.lol")
        ),
        servers = @Server(url = "/", description = "Current Server"),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class Application {

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
