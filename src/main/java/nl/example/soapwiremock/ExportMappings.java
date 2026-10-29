package nl.example.soapwiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Schrijft de in Java gedefinieerde stubs weg als WireMock JSON-mappings.
 * Die kun je daarna gebruiken met de standalone JAR of het Docker-image,
 * zonder Java-code.
 * <p>
 * Uitvoeren: {@code mvn compile exec:java -Dexec.mainClass=nl.example.soapwiremock.ExportMappings}
 * <br>
 * Resultaat: {@code target/wiremock/mappings/*.json}
 */
public final class ExportMappings {

    private ExportMappings() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "target/wiremock");
        Files.createDirectories(root.resolve("mappings"));
        Files.createDirectories(root.resolve("__files"));

        WireMockServer server = new WireMockServer(options()
                .dynamicPort()
                .usingFilesUnderDirectory(root.toString()));
        server.start();
        try {
            MockServerApp.registerStubs(new WireMock("localhost", server.port()));
            server.saveMappings();
            System.out.println("Mappings geschreven naar " + root.resolve("mappings").toAbsolutePath());
        } finally {
            server.stop();
        }
    }
}
