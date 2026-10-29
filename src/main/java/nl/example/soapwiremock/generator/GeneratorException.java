package nl.example.soapwiremock.generator;

/** Fout waardoor de generator niet verder kan (ongeldige WSDL, ontbrekende XSD, ...). */
public class GeneratorException extends RuntimeException {

    public GeneratorException(String message) {
        super(message);
    }

    public GeneratorException(String message, Throwable cause) {
        super(message, cause);
    }
}
