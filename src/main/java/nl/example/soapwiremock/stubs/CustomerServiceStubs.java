package nl.example.soapwiremock.stubs;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;
import nl.example.soapwiremock.generated.common.ErrorDetail;
import nl.example.soapwiremock.generated.customer.Customer;
import nl.example.soapwiremock.generated.customer.GetCustomerResponse;
import nl.example.soapwiremock.soap.SoapMessages;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingXPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * WireMock-stubs voor de CustomerService.
 * <p>
 * Responses worden opgebouwd uit JAXB-objecten (gegenereerd uit customer.xsd + common.xsd)
 * en bij het opbouwen gevalideerd tegen de XSD's.
 * <p>
 * Prioriteit in WireMock: een lager getal wint. De volgorde is daarom:
 * <ol>
 *     <li>specifieke foutscenario's (priority 1)</li>
 *     <li>specifieke klanten (priority 5)</li>
 *     <li>generieke fallback (priority 10)</li>
 * </ol>
 */
public class CustomerServiceStubs {

    public static final String ENDPOINT = "/ws/customer";
    public static final String NS_CUSTOMER = "http://example.com/customer/v1";
    public static final String SOAP_ACTION_GET_CUSTOMER = "http://example.com/customer/v1/GetCustomer";

    /** Prefixen die in de XPath-expressies van de request-matching worden gebruikt. */
    private static final Map<String, String> NAMESPACES = Map.of("cus", NS_CUSTOMER);

    /** Placeholder die na het marshallen wordt vervangen door een WireMock-template. */
    private static final String CUSTOMER_ID_PLACEHOLDER = "TEMPLATE_CUSTOMER_ID";
    private static final String CUSTOMER_ID_FROM_REQUEST =
            "{{xPath request.body '//*[local-name()=\"customerId\"]/text()'}}";

    private final WireMock wireMock;
    private final SoapMessages soap;

    public CustomerServiceStubs(WireMock wireMock, SoapMessages soap) {
        this.wireMock = wireMock;
        this.soap = soap;
    }

    /** GetCustomer met dit customerId geeft deze klant terug. */
    public void getCustomerReturns(Customer customer) {
        GetCustomerResponse response = new GetCustomerResponse();
        response.setCustomer(customer);

        wireMock.register(getCustomerRequest()
                .atPriority(5)
                .withRequestBody(customerIdEquals(customer.getCustomerId()))
                .willReturn(soapOk(soap.envelope(response))));
    }

    /** GetCustomer met dit customerId geeft een SOAP Fault (HTTP 500). */
    public void getCustomerNotFound(String customerId) {
        ErrorDetail detail = new ErrorDetail();
        detail.setCode("CUSTOMER_NOT_FOUND");
        detail.setMessage("Klant " + customerId + " bestaat niet");

        wireMock.register(getCustomerRequest()
                .atPriority(1)
                .withRequestBody(customerIdEquals(customerId))
                .willReturn(soapFault(soap.fault("soapenv:Client", "Customer not found", detail))));
    }

    /**
     * Fallback voor elk ander customerId: geeft een standaardklant terug waarbij het
     * customerId uit het request wordt overgenomen (WireMock response templating).
     *
     * @param template voorbeeldklant; het customerId van dit object wordt overschreven met een placeholder
     */
    public void getCustomerDefault(Customer template) {
        template.setCustomerId(CUSTOMER_ID_PLACEHOLDER);
        GetCustomerResponse response = new GetCustomerResponse();
        response.setCustomer(template);

        String body = soap.envelope(response).replace(CUSTOMER_ID_PLACEHOLDER, CUSTOMER_ID_FROM_REQUEST);

        wireMock.register(getCustomerRequest()
                .atPriority(10)
                .willReturn(soapOk(body).withTransformers("response-template")));
    }

    // ------------------------------------------------------------------ bouwstenen

    private static MappingBuilder getCustomerRequest() {
        return post(urlPathEqualTo(ENDPOINT))
                .withHeader("SOAPAction", containing("GetCustomer"))
                .withRequestBody(matchingXPath("//cus:GetCustomerRequest", NAMESPACES));
    }

    private static StringValuePattern customerIdEquals(String customerId) {
        if (customerId.contains("'")) {
            throw new IllegalArgumentException("customerId mag geen enkele quote bevatten: " + customerId);
        }
        return matchingXPath("//cus:GetCustomerRequest/cus:customerId[text()='" + customerId + "']", NAMESPACES);
    }

    private static ResponseDefinitionBuilder soapOk(String body) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", SoapMessages.CONTENT_TYPE)
                .withBody(body);
    }

    private static ResponseDefinitionBuilder soapFault(String body) {
        return aResponse()
                .withStatus(500)
                .withHeader("Content-Type", SoapMessages.CONTENT_TYPE)
                .withBody(body);
    }
}
