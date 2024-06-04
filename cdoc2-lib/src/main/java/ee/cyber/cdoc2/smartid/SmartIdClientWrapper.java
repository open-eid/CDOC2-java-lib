package ee.cyber.cdoc2.smartid;

import ee.sk.smartid.AuthenticationHash;
import ee.sk.smartid.AuthenticationResponseValidator;
import ee.sk.smartid.SmartIdAuthenticationResponse;
import ee.sk.smartid.SmartIdClient;
import ee.sk.smartid.exception.UnprocessableSmartIdResponseException;
import ee.sk.smartid.exception.permanent.ServerMaintenanceException;
import ee.sk.smartid.exception.useraccount.CertificateLevelMismatchException;
import ee.sk.smartid.exception.useraccount.DocumentUnusableException;
import ee.sk.smartid.exception.useraccount.UserAccountNotFoundException;
import ee.sk.smartid.exception.useraction.SessionTimeoutException;
import ee.sk.smartid.exception.useraction.UserRefusedException;
import ee.sk.smartid.exception.useraction.UserSelectedWrongVerificationCodeException;
import ee.sk.smartid.rest.dao.Interaction;
import ee.sk.smartid.rest.dao.SemanticsIdentifier;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;

import ee.cyber.cdoc2.exceptions.ConfigurationLoadingException;
import ee.cyber.cdoc2.exceptions.SmartIdClientException;


/**
 * Smart-ID Client
 */
public class SmartIdClientWrapper {

    private static final String CERT_FILE_NAME
        = "smartid/smartid_demo_server_trusted_ssl_certs.jks";
    private static final String CERT_NOT_FOUND = "Smart ID trusted SSL certificates not found";

    private final SmartIdClient smartIdClient;
    private final SmartIdConfigurationProperties smartIdClientConfig;
    private final AuthenticationResponseValidator authenticationResponseValidator;

    /**
     * Constructor for Smart-ID Client wrapper
     */
    public SmartIdClientWrapper() throws ConfigurationLoadingException {
        this.smartIdClientConfig = SmartIdConfigurationProperties.load();
        this.smartIdClient = new SmartIdClient();
        configureSmartIdClient();
        this.authenticationResponseValidator = new AuthenticationResponseValidator();
        setTrustedCertificatesToValidator(authenticationResponseValidator);
    }

    /**
     * Smart ID client configuration
     */
    private void configureSmartIdClient() throws ConfigurationLoadingException {
        smartIdClient.setHostUrl(smartIdClientConfig.getHostUrl());
        smartIdClient.setRelyingPartyUUID(smartIdClientConfig.getRelyingPartyUuid());
        smartIdClient.setRelyingPartyName(smartIdClientConfig.getRelyingPartyName());
        KeyStore trustedCerts = readTrustedCertificates();
        smartIdClient.setTrustStore(trustedCerts);
    }

    /**
     * Authentication request to {@code /authentication/etsi/:semantics-identifier}.
     * @param semanticsIdentifier ETSI semantics identifier
     * @param authenticationHash  Base64 encoded hash function output to be signed
     * @param certificationLevel  Level of certificate requested, can either be
     *                            {@code QUALIFIED} or {@code ADVANCED}
     * @return SmartIdAuthenticationResponse object
     */
    public SmartIdAuthenticationResponse authenticate(
        SemanticsIdentifier semanticsIdentifier,
        AuthenticationHash authenticationHash,
        String certificationLevel
    ) throws UserAccountNotFoundException,
        UserRefusedException,
        UserSelectedWrongVerificationCodeException,
        SessionTimeoutException,
        DocumentUnusableException,
        ServerMaintenanceException,
        SmartIdClientException {

        SmartIdAuthenticationResponse authResponse = smartIdClient
            .createAuthentication()
            .withSemanticsIdentifier(semanticsIdentifier)
            .withAuthenticationHash(authenticationHash)
            .withCertificateLevel(certificationLevel)
            // Smart-ID app will display verification code to the user and user must insert PIN1
            .withAllowedInteractionsOrder(
                Collections.singletonList(Interaction.displayTextAndPIN("Log in to self-service?"))
            )
            .withShareMdClientIpAddress(true)
            .authenticate();

        validateResponse(authResponse);

        return authResponse;
    }

    private void validateResponse(
        SmartIdAuthenticationResponse authResponse
    ) throws SmartIdClientException {

        try {
            authenticationResponseValidator.validate(authResponse);
        } catch (
            UnprocessableSmartIdResponseException | CertificateLevelMismatchException ex
        ) {
            throw new SmartIdClientException(
                "Smart ID authentication response validation has failed", ex
            );
        }
    }

    /**
     * Trusted certificates must be set up to {@link AuthenticationResponseValidator}.
     * @param validator smart id client validation object
     */
    private void setTrustedCertificatesToValidator(AuthenticationResponseValidator validator)
        throws ConfigurationLoadingException {
        for (X509Certificate cert : getTrustedCertificates()) {
            validator.addTrustedCACertificate(cert);
        }
    }

    private List<X509Certificate> getTrustedCertificates() throws ConfigurationLoadingException {
        try {
            KeyStore keystore = readTrustedCertificates();
            Enumeration<String> aliases = keystore.aliases();

            List<X509Certificate> certs = new LinkedList<>();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                X509Certificate certificate = (X509Certificate) keystore.getCertificate(alias);
                certs.add(certificate);
            }

            return certs;
        } catch (KeyStoreException ex) {
            throw new ConfigurationLoadingException(
                "Failed to load trusted certificates for Smart ID authentication "
                    + "response validation", ex
            );
        }
    }

    /**
     * Read trusted certificates for Smart ID client secure TLS transport
     */
    private KeyStore readTrustedCertificates() throws ConfigurationLoadingException {
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(CERT_FILE_NAME)) {
            if (null == is) {
                throw new ConfigurationLoadingException(CERT_NOT_FOUND);
            } else {
                KeyStore trustStore = KeyStore.getInstance("JKS");
                trustStore.load(is, smartIdClientConfig.getTrustStorePassword().toCharArray());
                return trustStore;
            }
        } catch (CertificateException
                 | IOException
                 | NoSuchAlgorithmException
                 | KeyStoreException ex) {
            throw new ConfigurationLoadingException(
                "Failed to load trusted certificates for Smart ID authentication", ex
            );
        }
    }

}
