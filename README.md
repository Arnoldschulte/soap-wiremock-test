# soap-wiremock-test

Basisopzet om SOAP-responses te simuleren met **WireMock**, op basis van Java-klassen die met **JAXB** uit meerdere **XSD's** worden gegenereerd.

- Responses worden in Java opgebouwd met de gegenereerde klassen, dus typeveilig en met code completion in IntelliJ.
- Elke response wordt bij het opbouwen **gevalideerd tegen de XSD's**. Een mock die niet aan het contract voldoet, faalt direct.
- De stubs zijn te gebruiken in JUnit-tests, als lokale mockserver en, na export, als JSON-mappings voor WireMock standalone of Docker.

## Vereisten

- Java 17 of hoger
- Maven 3.9+
- IntelliJ IDEA (optioneel)

## Projectstructuur

```
src/main/resources/xsd/
├── common.xsd          gedeelde typen (Address, ErrorDetail)
├── customer.xsd        berichten van de CustomerService, importeert common.xsd
└── bindings.xjb        XSD → Java-package per schema

src/main/java/nl/example/soapwiremock/
├── soap/SoapMessages.java              JAXB ⇄ SOAP-envelope, inclusief XSD-validatie
├── stubs/CustomerServiceStubs.java     WireMock-stubs per scenario
├── SampleData.java                     testdata met de gegenereerde klassen
├── MockServerApp.java                  start een lokale mockserver
└── ExportMappings.java                 schrijft de stubs weg als JSON-mappings

src/test/java/...                       JUnit 5-tests met @WireMockTest
requests/GetCustomerRequest.xml         voorbeeldrequest voor curl / SoapUI / Postman

target/generated-sources/xjc/           gegenereerde JAXB-klassen (niet in git)
```

## Hoe het werkt

1. De `jaxb-maven-plugin` genereert tijdens `generate-sources` klassen uit alle XSD's:
   - `nl.example.soapwiremock.generated.customer` (`GetCustomerRequest`, `GetCustomerResponse`, `Customer`, ...)
   - `nl.example.soapwiremock.generated.common` (`Address`, `ErrorDetail`)
2. `SoapMessages` marshalt een JAXB-object, valideert het tegen de XSD en verpakt het in een SOAP 1.1-envelope. Andersom leest het een payload uit de `Body` of uit het `detail` van een Fault.
3. `CustomerServiceStubs` registreert WireMock-stubs die matchen op endpoint, `SOAPAction` en via XPath op de inhoud van het request.

| Scenario | Matcht op | Priority | Response |
|---|---|---|---|
| Bekende klant | `customerId = 12345` | 5 | 200 met klantgegevens |
| Onbekende klant | `customerId = 99999` | 1 | 500 met SOAP Fault en `ErrorDetail` |
| Fallback | elke andere `GetCustomer` | 10 | 200, `customerId` overgenomen uit het request (response templating) |

In WireMock wint het laagste priority-getal.

## Aan de slag in IntelliJ

1. **File → Open** en kies de map met `pom.xml`. Open het als Maven-project.
2. Klik in het Maven-venster op **Reload All Maven Projects**. Kies daarna in het projectmenu **Maven → Generate Sources and Update Folders**.
   De gegenereerde klassen verschijnen in `target/generated-sources/xjc` en IntelliJ markeert die map als source root.
3. Run de tests. Klik rechts op `src/test/java` en kies **Run 'All Tests'**, of gebruik de terminal:

   ```bash
   mvn verify
   ```

Pas je een XSD aan? Draai dan opnieuw **Generate Sources and Update Folders**, of `mvn generate-sources`.

## Lokale mockserver

```bash
mvn compile exec:java                      # poort 8080
mvn compile exec:java -Dexec.args="9090"   # andere poort
```

Testen:

```bash
curl -X POST http://localhost:8080/ws/customer \
  -H "Content-Type: text/xml; charset=UTF-8" \
  -H "SOAPAction: http://example.com/customer/v1/GetCustomer" \
  -d @requests/GetCustomerRequest.xml
```

Geladen mappings bekijken: <http://localhost:8080/__admin/mappings>

## Exporteren naar JSON-mappings

Wil je de mocks zonder Java draaien, bijvoorbeeld in Docker in een testomgeving? Exporteer ze dan als JSON:

```bash
mvn compile exec:java -Dexec.mainClass=nl.example.soapwiremock.ExportMappings
```

De mappings komen in `target/wiremock/mappings`. Start ze met Docker:

```bash
docker run -it --rm -p 8080:8080 \
  -v "$PWD/target/wiremock:/home/wiremock" \
  wiremock/wiremock:3.13.1 --verbose
```

## Eigen XSD's toevoegen

1. Zet je XSD's in `src/main/resources/xsd/`. Bewaar ze met dezelfde onderlinge mappenstructuur, zodat de `schemaLocation` van imports en includes blijft kloppen.
2. Voeg per XSD een blok toe aan `bindings.xjb` met het gewenste Java-package.
3. Draai `mvn generate-sources` en controleer de klassen in `target/generated-sources/xjc`.
4. Maak een `SoapMessages` aan met je **top-level** XSD's. Geïmporteerde XSD's worden automatisch meegeladen:

   ```java
   SoapMessages soap = SoapMessages.validatingAgainst("xsd/order.xsd", "xsd/customer.xsd");
   ```

5. Maak per service een stub-klasse naar het voorbeeld van `CustomerServiceStubs`. Gebruik daarin één methode per scenario en match op `SOAPAction` en XPath.

### Tips

- **Element zonder `@XmlRootElement`.** Een globaal element met een *named* type, zoals `<xs:element name="X" type="tns:XType"/>`, krijgt geen `@XmlRootElement`. Gebruik dan `new ObjectFactory().createX(value)`; dat levert een `JAXBElement` op, en die kun je gewoon aan `soap.envelope(...)` meegeven.
- **SOAP 1.2.** Gebruik envelope-namespace `http://www.w3.org/2003/05/soap-envelope` en content-type `application/soap+xml`. Match dan niet op de `SOAPAction`-header, maar op de `action`-parameter in de `Content-Type`.
- **Klassen uit een WSDL.** De plugin kan ook direct uit een WSDL genereren. Voeg daarvoor `<schemaLanguage>WSDL</schemaLanguage>` toe aan de pluginconfiguratie.
- **Namespace-problemen bij matching.** Start WireMock met `--verbose`. Bij een 404 laat WireMock dan zien welke stub het dichtst in de buurt kwam en waarom die niet matchte.
