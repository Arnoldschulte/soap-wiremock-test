# WireMock-image met de SOAP-mocks uit deze map.
#
# Bouwen (publieke image):
#   docker build -t customer-soap-mock wiremock/
#
# Bouwen met een eigen/interne registry:
#   docker build -t customer-soap-mock \
#     --build-arg WIREMOCK_IMAGE=registry.example.nl/wiremock/wiremock:2.32.0 wiremock/
#
# Draaien:
#   docker run --rm -p 8080:8080 customer-soap-mock
ARG WIREMOCK_IMAGE=wiremock/wiremock:3.13.1
FROM ${WIREMOCK_IMAGE}

COPY mappings /home/wiremock/mappings
COPY __files /home/wiremock/__files

# --global-response-templating werkt in zowel WireMock 2.x als 3.x.
# (In 2.x staat templating standaard uit; zonder deze vlag wordt {{...}} letterlijk teruggegeven.)
CMD ["--global-response-templating", "--verbose"]
