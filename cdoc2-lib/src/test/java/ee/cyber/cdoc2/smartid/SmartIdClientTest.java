package ee.cyber.cdoc2.smartid;

import ee.sk.smartid.AuthenticationHash;
import ee.sk.smartid.AuthenticationIdentity;
import ee.sk.smartid.SmartIdAuthenticationResponse;
import ee.sk.smartid.rest.dao.SemanticsIdentifier;

import org.junit.jupiter.api.Test;

import ee.cyber.cdoc2.client.smartid.SmartIdClient;
import ee.cyber.cdoc2.exceptions.ConfigurationLoadingException;
import ee.cyber.cdoc2.exceptions.CdocSmartIdClientException;

import static ee.cyber.cdoc2.ClientConfigurationUtil.getSmartIdConfiguration;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;


class SmartIdClientTest {

    private static final String CERT_LEVEL_ADVANCED = "ADVANCED";
    private static final String CERT_LEVEL_QUALIFIED = "QUALIFIED";
    private static final String IDENTITY_NUMBER = "30303039914";
    // private static final String IDENTITY_NUMBER = "38001085718";

    private final SmartIdClient smartIdClient;

    SmartIdClientTest() throws ConfigurationLoadingException {
        getSmartIdConfiguration();
        this.smartIdClient = new SmartIdClient();
    }

    @Test
    void successfullyAuthenticateUser() throws Exception {
        SemanticsIdentifier semanticsIdentifier = new SemanticsIdentifier(
            // 3 character identity type
            // (PAS-passport, IDC-national identity card or PNO - (national) personal number)
            SemanticsIdentifier.IdentityType.PNO,
            SemanticsIdentifier.CountryCode.EE,
            IDENTITY_NUMBER
        );

        AuthenticationHash authenticationHash = AuthenticationHash.generateRandomHash();
        SmartIdAuthenticationResponse authResponse = smartIdClient.authenticate(
            semanticsIdentifier,
            authenticationHash,
            CERT_LEVEL_QUALIFIED
        );

        assertNotNull(authResponse);
        assertEquals("OK", authResponse.getEndResult());

        AuthenticationIdentity returnedIdentifier = smartIdClient.validateResponse(authResponse);

        assertEquals(IDENTITY_NUMBER, returnedIdentifier.getIdentityNumber());

       // assertEquals("ddsfdsf", authResponse.getCertificate(), "Just to print cert out");
        // assertEquals("yydrttt", authResponse.getSignatureValueInBase64(), "Just to print signature out");
    }

    @Test
    void failAuthenticationOfNonExistingUser() {
        String nonExistingIdNumber = "12345678900";
        SemanticsIdentifier semanticsIdentifier = new SemanticsIdentifier(
            SemanticsIdentifier.IdentityType.PNO,
            SemanticsIdentifier.CountryCode.EE,
            nonExistingIdNumber
        );

        AuthenticationHash authenticationHash = AuthenticationHash.generateRandomHash();
        CdocSmartIdClientException exception = assertThrows(CdocSmartIdClientException.class,
            () -> smartIdClient.authenticate(
                semanticsIdentifier,
                authenticationHash,
                CERT_LEVEL_QUALIFIED
            )
        );
    }

}
