package nl.example.soapwiremock;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import nl.example.soapwiremock.generated.common.ErrorDetail;
import nl.example.soapwiremock.generated.customer.Customer;
import nl.example.soapwiremock.generated.customer.CustomerStatus;
import nl.example.soapwiremock.generated.customer.GetCustomerRequest;
import nl.example.soapwiremock.generated.customer.GetCustomerResponse;
import nl.example.soapwiremock.soap.SoapMessages;
import nl.example.soapwiremock.stubs.CustomerServiceStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static nl.example.soapwiremock.stubs.CustomerServiceStubs.SOAP_ACTION_GET_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
class CustomerServiceStubsTest {

    private final SoapMessages soap = SoapMessages.validatingAgainst("xsd/customer.xsd");

    private SoapTestClient client;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        CustomerServiceStubs stubs = new CustomerServiceStubs(wm.getWireMock(), soap);
        stubs.getCustomerReturns(SampleData.janJansen());
        stubs.getCustomerNotFound(SampleData.UNKNOWN_CUSTOMER_ID);
        stubs.getCustomerDefault(SampleData.defaultCustomer());

        client = new SoapTestClient(wm.getHttpBaseUrl() + CustomerServiceStubs.ENDPOINT);
    }

    @Test
    void geeftGeconfigureerdeKlantTerug() throws Exception {
        HttpResponse<String> response = client.call(SOAP_ACTION_GET_CUSTOMER, getCustomerRequest("12345"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("text/xml"));

        Customer customer = soap.bodyPayload(response.body(), GetCustomerResponse.class).getCustomer();
        assertThat(customer.getCustomerId()).isEqualTo("12345");
        assertThat(customer.getFirstName()).isEqualTo("Jan");
        assertThat(customer.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
        // Adres komt uit de geïmporteerde common.xsd (andere namespace)
        assertThat(customer.getAddress().getCity()).isEqualTo("Utrecht");
    }

    @Test
    void geeftSoapFaultVoorOnbekendeKlant() throws Exception {
        HttpResponse<String> response = client.call(SOAP_ACTION_GET_CUSTOMER, getCustomerRequest("99999"));

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("<faultcode>soapenv:Client</faultcode>");

        ErrorDetail detail = soap.faultDetail(response.body(), ErrorDetail.class);
        assertThat(detail.getCode()).isEqualTo("CUSTOMER_NOT_FOUND");
    }

    @Test
    void fallbackNeemtCustomerIdOverUitRequest() throws Exception {
        HttpResponse<String> response = client.call(SOAP_ACTION_GET_CUSTOMER, getCustomerRequest("55555"));

        assertThat(response.statusCode()).isEqualTo(200);
        Customer customer = soap.bodyPayload(response.body(), GetCustomerResponse.class).getCustomer();
        assertThat(customer.getCustomerId()).isEqualTo("55555");
        assertThat(customer.getLastName()).isEqualTo("Klant");
    }

    @Test
    void verkeerdeSoapActionMatchtGeenStub() throws Exception {
        HttpResponse<String> response = client.call("http://example.com/customer/v1/DeleteCustomer",
                getCustomerRequest("12345"));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    private String getCustomerRequest(String customerId) {
        GetCustomerRequest request = new GetCustomerRequest();
        request.setCustomerId(customerId);
        return soap.envelope(request);
    }
}
