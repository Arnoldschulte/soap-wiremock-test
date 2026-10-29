package nl.example.soapwiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import nl.example.soapwiremock.soap.SoapMessages;
import nl.example.soapwiremock.stubs.CustomerServiceStubs;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Start een lokale WireMock-server met de SOAP-stubs, zodat je er met SoapUI,
 * Postman, curl of je eigen applicatie tegenaan kunt praten.
 * <p>
 * Starten: {@code mvn compile exec:java} (optioneel {@code -Dexec.args="9090"} voor een andere poort)
 */
public final class MockServerApp {

    private MockServerApp() {
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        WireMockServer server = new WireMockServer(options().port(port));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        registerStubs(new WireMock("localhost", server.port()));

        System.out.printf("SOAP-mock draait op http://localhost:%d%s%n", server.port(), CustomerServiceStubs.ENDPOINT);
        System.out.printf("Mappings bekijken:   http://localhost:%d/__admin/mappings%n", server.port());
        System.out.println("Stoppen met Ctrl+C");
    }

    static void registerStubs(WireMock wireMock) {
        CustomerServiceStubs customers = new CustomerServiceStubs(
                wireMock, SoapMessages.validatingAgainst("xsd/customer.xsd"));

        customers.getCustomerReturns(SampleData.janJansen());
        customers.getCustomerNotFound(SampleData.UNKNOWN_CUSTOMER_ID);
        customers.getCustomerDefault(SampleData.defaultCustomer());
    }
}
