package nl.example.soapwiremock.generator;

import com.github.tomakehurst.wiremock.WireMockServer;
import nl.example.soapwiremock.SampleData;
import nl.example.soapwiremock.SoapTestClient;
import nl.example.soapwiremock.generated.common.ErrorDetail;
import nl.example.soapwiremock.generated.customer.GetCustomerRequest;
import nl.example.soapwiremock.generated.customer.GetCustomerResponse;
import nl.example.soapwiremock.generated.customer.UpdateCustomerRequest;
import nl.example.soapwiremock.generated.customer.UpdateCustomerResponse;
import nl.example.soapwiremock.soap.SoapMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StubGeneratorTest {

    private static final Path WSDL = Path.of("src/main/resources/wsdl/CustomerService.wsdl");
    private static final String IMAGE = "wiremock/wiremock:3.13.1";

    /** Requests bouwen we met validatie, responses lezen we zonder (voorbeeldwaarden zijn niet altijd XSD-geldig). */
    private final SoapMessages requests = SoapMessages.validatingAgainst("xsd/customer.xsd");
    private final SoapMessages responses = SoapMessages.withoutValidation();

    @TempDir
    Path out;

    @Test
    void genereertMappingsEnFilesPerOperatieEnFault() throws Exception {
        Report report = StubGenerator.generate(WSDL, out, false, IMAGE);

        assertThat(report.written()).contains(
                "mappings/CustomerService/GetCustomer.json",
                "mappings/CustomerService/GetCustomer_CustomerNotFound.json",
                "mappings/CustomerService/UpdateCustomer.json",
                "__files/CustomerService/GetCustomerResponse.xml",
                "__files/CustomerService/GetCustomer_CustomerNotFound.xml",
                "__files/CustomerService/UpdateCustomerResponse.xml",
                "Dockerfile",
                "README.md");

        String mapping = Files.readString(out.resolve("mappings/CustomerService/GetCustomer.json"));
        assertThat(mapping)
                .contains("\"urlPath\": \"/ws/customer\"")
                .contains("SOAPAction")
                .contains("//req:GetCustomerRequest")
                .contains("\"bodyFileName\": \"CustomerService/GetCustomerResponse.xml\"");

        String response = Files.readString(out.resolve("__files/CustomerService/GetCustomerResponse.xml"));
        assertThat(response).contains("http://schemas.xmlsoap.org/soap/envelope/", "GetCustomerResponse", "postalCode");

        assertThat(Files.readString(out.resolve("Dockerfile"))).contains("ARG WIREMOCK_IMAGE=" + IMAGE);
    }

    @Test
    void gegenereerdeStubsWerkenInWireMock() throws Exception {
        StubGenerator.generate(WSDL, out, false, IMAGE);

        WireMockServer server = new WireMockServer(options().dynamicPort().usingFilesUnderDirectory(out.toString()));
        server.start();
        try {
            SoapTestClient client = new SoapTestClient(server.baseUrl() + "/ws/customer");

            // GetCustomer -> default-response
            HttpResponse<String> get = client.call(
                    "http://example.com/customer/v1/GetCustomer", getCustomerRequest("12345"));
            assertThat(get.statusCode()).isEqualTo(200);
            assertThat(responses.bodyPayload(get.body(), GetCustomerResponse.class).getCustomer()).isNotNull();

            // UpdateCustomer -> eigen mapping op basis van het request-element
            UpdateCustomerRequest update = new UpdateCustomerRequest();
            update.setCustomer(SampleData.janJansen());
            HttpResponse<String> updated = client.call(
                    "\"http://example.com/customer/v1/UpdateCustomer\"", requests.envelope(update));
            assertThat(updated.statusCode()).isEqualTo(200);
            assertThat(responses.bodyPayload(updated.body(), UpdateCustomerResponse.class)).isNotNull();

            // Fault via de X-Mock-Fault-header
            HttpResponse<String> fault = client.call("http://example.com/customer/v1/GetCustomer",
                    getCustomerRequest("12345"), Map.of(StubWriter.FAULT_HEADER, "CustomerNotFound"));
            assertThat(fault.statusCode()).isEqualTo(500);
            assertThat(responses.faultDetail(fault.body(), ErrorDetail.class)).isNotNull();

            // Request-element van GetCustomer met de SOAPAction van UpdateCustomer matcht niets
            HttpResponse<String> mismatch = client.call(
                    "http://example.com/customer/v1/UpdateCustomer", getCustomerRequest("12345"));
            assertThat(mismatch.statusCode()).isEqualTo(404);
        } finally {
            server.stop();
        }
    }

    @Test
    void bestaandeBestandenWordenAlleenMetOverwriteOverschreven() throws Exception {
        StubGenerator.generate(WSDL, out, false, IMAGE);
        Path file = out.resolve("__files/CustomerService/GetCustomerResponse.xml");
        Files.writeString(file, "handmatig aangepast");

        Report second = StubGenerator.generate(WSDL, out, false, IMAGE);
        assertThat(second.written()).isEmpty();
        assertThat(second.skipped()).contains("__files/CustomerService/GetCustomerResponse.xml");
        assertThat(Files.readString(file)).isEqualTo("handmatig aangepast");

        Report third = StubGenerator.generate(WSDL, out, true, IMAGE);
        assertThat(third.skipped()).isEmpty();
        assertThat(Files.readString(file)).contains("GetCustomerResponse");
    }

    @Test
    void geeftDuidelijkeFoutBijOntbrekendeWsdl() {
        assertThatThrownBy(() -> StubGenerator.generate(Path.of("bestaat-niet.wsdl"), out, false, IMAGE))
                .isInstanceOf(GeneratorException.class)
                .hasMessageContaining("WSDL niet gevonden");
    }

    @Test
    void parseertArgumentenInBeideVormen() {
        assertThat(StubGenerator.parseArgs(new String[]{"--wsdl=a.wsdl", "--out", "x", "--overwrite=true"}))
                .containsEntry("wsdl", "a.wsdl")
                .containsEntry("out", "x")
                .containsEntry("overwrite", "true");
    }

    private String getCustomerRequest(String customerId) {
        GetCustomerRequest request = new GetCustomerRequest();
        request.setCustomerId(customerId);
        return requests.envelope(request);
    }
}
