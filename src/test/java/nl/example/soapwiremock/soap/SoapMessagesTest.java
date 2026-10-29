package nl.example.soapwiremock.soap;

import nl.example.soapwiremock.SampleData;
import nl.example.soapwiremock.generated.customer.Customer;
import nl.example.soapwiremock.generated.customer.GetCustomerResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoapMessagesTest {

    private final SoapMessages soap = SoapMessages.validatingAgainst("xsd/customer.xsd");

    @Test
    void envelopeEnTerugLezenLevertZelfdeObjectOp() {
        GetCustomerResponse response = new GetCustomerResponse();
        response.setCustomer(SampleData.janJansen());

        String xml = soap.envelope(response);

        assertThat(xml).contains("Envelope", "Body", "http://example.com/customer/v1", "http://example.com/common/v1");
        Customer roundTrip = soap.bodyPayload(xml, GetCustomerResponse.class).getCustomer();
        assertThat(roundTrip.getLastName()).isEqualTo("Jansen");
        assertThat(roundTrip.getAddress().getPostalCode()).isEqualTo("3511AB");
    }

    @Test
    void ongeldigePayloadWordtAfgekeurdDoorXsd() {
        Customer customer = SampleData.janJansen();
        customer.setLastName(null); // verplicht element volgens customer.xsd
        GetCustomerResponse response = new GetCustomerResponse();
        response.setCustomer(customer);

        assertThatThrownBy(() -> soap.envelope(response))
                .isInstanceOf(SoapMessageException.class)
                .hasMessageContaining("niet geldig volgens de XSD");
    }

    @Test
    void ongeldigePostcodeUitGeimporteerdeXsdWordtAfgekeurd() {
        Customer customer = SampleData.janJansen();
        customer.getAddress().setPostalCode("ABC"); // patroon uit common.xsd
        GetCustomerResponse response = new GetCustomerResponse();
        response.setCustomer(customer);

        assertThatThrownBy(() -> soap.envelope(response))
                .isInstanceOf(SoapMessageException.class);
    }

    @Test
    void validateKeurtHandgeschrevenBerichtMetFoutePostcodeAf() {
        String xml = """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                                  xmlns:cus="http://example.com/customer/v1"
                                  xmlns:com="http://example.com/common/v1">
                  <soapenv:Body>
                    <cus:GetCustomerResponse>
                      <cus:customer>
                        <cus:customerId>1</cus:customerId>
                        <cus:firstName>A</cus:firstName>
                        <cus:lastName>B</cus:lastName>
                        <cus:status>ACTIVE</cus:status>
                        <cus:address>
                          <com:street>s</com:street>
                          <com:houseNumber>1</com:houseNumber>
                          <com:postalCode>ABC</com:postalCode>
                          <com:city>c</com:city>
                        </cus:address>
                      </cus:customer>
                    </cus:GetCustomerResponse>
                  </soapenv:Body>
                </soapenv:Envelope>
                """;

        assertThatThrownBy(() -> soap.validate(xml))
                .isInstanceOf(SoapMessageException.class)
                .hasMessageContaining("'ABC'");
    }

    @Test
    void zonderValidatieWordtOngeldigePayloadWelGemarshald() {
        Customer customer = SampleData.janJansen();
        customer.getAddress().setPostalCode("ABC");
        GetCustomerResponse response = new GetCustomerResponse();
        response.setCustomer(customer);

        assertThat(SoapMessages.withoutValidation().envelope(response)).contains("ABC");
    }
}
