# soap-wiremock-test

Basisopzet om SOAP-responses te simuleren met **WireMock**, op basis van Java-klassen die met **JAXB** uit meerdere **XSD's** worden gegenereerd.

- Responses worden in Java opgebouwd met de gegenereerde klassen, dus typeveilig en met code completion in IntelliJ.
- Elke response wordt bij het opbouwen **gevalideerd tegen de XSD's**. Een mock die niet aan het contract voldoet, faalt direct.
- Er zijn twee manieren om de mocks te gebruiken:
  - **Java-stubs** voor JUnit-tests en als lokale mockserver.
  - **`wiremock/`-map** met `mappings` en `__files` plus een Dockerfile, voor een mock-image in een testomgeving. Een test laadt die map in WireMock en valideert alle response-bestanden tegen de XSD's.

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

src/test/java/...                       JUnit 5-tests (Java-stubs én de wiremock/-map)
wiremock/
├── Dockerfile                          image op basis van wiremock/wiremock
├── mappings/customer/*.json            request-matching per scenario
└── __files/customer/*.xml              SOAP-responses
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

## Docker-image met mappings en `__files`

De map `wiremock/` volgt de standaardindeling van WireMock. De `Dockerfile` kopieert die map in het image:

```dockerfile
ARG WIREMOCK_IMAGE=wiremock/wiremock:3.13.1
FROM ${WIREMOCK_IMAGE}

COPY mappings /home/wiremock/mappings
COPY __files /home/wiremock/__files

CMD ["--global-response-templating", "--verbose"]
```

Bouwen en draaien:

```bash
docker build -t customer-soap-mock wiremock/
docker run --rm -p 8080:8080 customer-soap-mock
```

Een image uit een interne registry gebruik je via een build-argument:

```bash
docker build -t customer-soap-mock \
  --build-arg WIREMOCK_IMAGE=registry.example.nl/wiremock/wiremock:2.32.0 wiremock/
```

### Waarom `--global-response-templating`?

In **WireMock 2.x** staat response templating standaard uit. Zonder deze vlag komt `{{xPath ...}}` letterlijk in de response terecht. De vlag werkt in zowel 2.x als 3.x.

Met de vlag aan wordt elke response als template behandeld. Gebruik in een XML-bestand dus alleen `{{` als je echt een template bedoelt.

### Werkwijze voor een nieuw scenario

1. Maak een response in `wiremock/__files/<service>/`. Een goed startpunt is een bericht dat je met de Java-code genereert via `SoapMessages.envelope(...)`. Dan weet je zeker dat het klopt met de XSD's.
2. Maak een mapping in `wiremock/mappings/<service>/` die met `bodyFileName` naar dat bestand verwijst.
3. Draai `mvn verify`. `WiremockFolderTest` doet dan twee dingen:
   - het valideert **elk** XML-bestand in `__files` tegen de XSD's (bij een Fault de inhoud van `<detail>`);
   - het start WireMock met deze map en roept de scenario's aan.

Let op: een template in een veld met een beperkt type (bijv. een postcode-patroon of een datum) is in het bestand zelf niet XSD-geldig. Gebruik templates daarom in `xs:string`-velden, of pas de validatietest aan voor zo'n bestand.

## Exporteren naar JSON-mappings

Definieer je de mocks liever in Java, dan kun je ze ook als JSON exporteren. WireMock schrijft de bodies daarbij inline in de mapping, dus zonder `__files`:

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
