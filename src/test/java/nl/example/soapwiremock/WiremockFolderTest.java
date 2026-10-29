package nl.example.soapwiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import nl.example.soapwiremock.generated.common.ErrorDetail;
import nl.example.soapwiremock.generated.customer.Customer;
import nl.example.soapwiremock.generated.customer.GetCustomerRequest;
import nl.example.soapwiremock.generated.customer.GetCustomerResponse;
import nl.example.soapwiremock.soap.SoapMessages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static nl.example.soapwiremock.stubs.CustomerServiceStubs.SOAP_ACTION_GET_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Test de inhoud van de map {@code wiremock/} (mappings + __files), dus precies wat
 * de Dockerfile in het image kopieert, zonder dat Docker nodig is.
 */
class WiremockFolderTest {

    private static final Path WIREMOCK_ROOT = Path.of("wiremock");
    private static final SoapMessages SOAP = SoapMessages.validatingAgainst("xsd/customer.xsd");

    /** Elk bestand in __files moet een welgevormd SOAP-bericht zijn met een XSD-conforme payload. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("responseFiles")
    void responseBestandIsGeldigVolgensXsd(Path file) throws IOException {
        String xml = Files.readString(file);
        assertThatCode(() -> SOAP.validate(xml)).doesNotThrowAnyException();
    }

    static Stream<Path> responseFiles() throws IOException {
        Path files = WIREMOCK_ROOT.resolve("__files");
        try (Stream<Path> walk = Files.walk(files)) {
            return walk.filter(p -> p.toString().endsWith(".xml")).sorted().toList().stream();
        }
    }

    /** Start WireMock met de map als root, met dezelfde instelling als de Dockerfile. */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class MappingsUitDeMap {

        private WireMockServer server;
        private SoapTestClient client;

        @BeforeAll
        void start() {
            server = new WireMockServer(options()
                    .dynamicPort()
                    .usingFilesUnderDirectory(WIREMOCK_ROOT.toString())
                    .globalTemplating(true));
            server.start();
            client = new SoapTestClient(server.baseUrl() + "/ws/customer");
        }

        @AfterAll
        void stop() {
            server.stop();
        }

        @Test
        void bekendeKlant() throws Exception {
            HttpResponse<String> response = client.call(SOAP_ACTION_GET_CUSTOMER, request("12345"));

            assertThat(response.statusCode()).isEqualTo(200);
            Customer customer = SOAP.bodyPayload(response.body(), GetCustomerResponse.class).getCustomer();
            assertThat(customer.getLastName()).isEqualTo("Jansen");
            assertThat(customer.getAddress().getCity()).isEqualTo("Utrecht");
        }

        @Test
        void onbekendeKlantGeeftFault() throws Exception {
            HttpResponse<String> response = client.call(SOAP_ACTION_GET_CUSTOMER, request("99999"));

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(SOAP.faultDetail(response.body(), ErrorDetail.class).getCode())
                    .isEqualTo("CUSTOMER_NOT_FOUND");
        }

        @Test
        void fallbackNeemtCustomerIdOver() throws Exception {
            HttpResponse<String> response = client.call(SOAP_ACTION_GET_CUSTOMER, request("55555"));

            assertThat(response.statusCode()).isEqualTo(200);
            // Na templating moet de response nog steeds schema-conform zijn
            SOAP.validate(response.body());
            Customer customer = SOAP.bodyPayload(response.body(), GetCustomerResponse.class).getCustomer();
            assertThat(customer.getCustomerId()).isEqualTo("55555");
        }

        private String request(String customerId) {
            GetCustomerRequest request = new GetCustomerRequest();
            request.setCustomerId(customerId);
            return SOAP.envelope(request);
        }
    }
}
