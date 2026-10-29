package nl.example.soapwiremock;

import nl.example.soapwiremock.generated.common.Address;
import nl.example.soapwiremock.generated.customer.Customer;
import nl.example.soapwiremock.generated.customer.CustomerStatus;

/** Testdata, opgebouwd met de gegenereerde JAXB-klassen. */
public final class SampleData {

    public static final String KNOWN_CUSTOMER_ID = "12345";
    public static final String UNKNOWN_CUSTOMER_ID = "99999";

    private SampleData() {
    }

    public static Customer janJansen() {
        return customer(KNOWN_CUSTOMER_ID, "Jan", "Jansen", "jan.jansen@example.com", CustomerStatus.ACTIVE,
                address("Dorpsstraat", "1", "3511AB", "Utrecht"));
    }

    public static Customer defaultCustomer() {
        return customer("00000", "Test", "Klant", null, CustomerStatus.ACTIVE,
                address("Teststraat", "99", "1000AA", "Amsterdam"));
    }

    public static Customer customer(String id, String firstName, String lastName, String email,
                                    CustomerStatus status, Address address) {
        Customer customer = new Customer();
        customer.setCustomerId(id);
        customer.setFirstName(firstName);
        customer.setLastName(lastName);
        customer.setEmail(email);
        customer.setStatus(status);
        customer.setAddress(address);
        return customer;
    }

    public static Address address(String street, String houseNumber, String postalCode, String city) {
        Address address = new Address();
        address.setStreet(street);
        address.setHouseNumber(houseNumber);
        address.setPostalCode(postalCode);
        address.setCity(city);
        return address;
    }
}
