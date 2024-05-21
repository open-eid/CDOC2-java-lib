package ee.cyber.cdoc2.client;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ee.cyber.cdoc2.client.api.ApiException;
import ee.cyber.cdoc2.client.api.ApiResponse;
import ee.cyber.cdoc2.client.api.Cdoc2KeySharesApi;
import ee.cyber.cdoc2.client.model.KeyShare;
import ee.cyber.cdoc2.client.model.NonceResponse;

import static ee.cyber.cdoc2.client.ApiClientUtil.extractIdFromHeader;


/**
 * Client for creating and getting CDOC2 key shares from key server.
 *  Provides Builder to initialize regular TLS from PKCS11 (smart-card) or PKCS12 (software) key
 *  stores.
 */
public final class Cdoc2KeySharesApiClient extends KeySharesClientBuilder {

    private static final Logger log = LoggerFactory.getLogger(Cdoc2KeySharesApiClient.class);

    private final Cdoc2KeySharesApi sharesApi;

    Cdoc2KeySharesApiClient(Cdoc2KeySharesApi shareApi) {
        this.sharesApi = shareApi;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Cdoc2KeySharesApiClient that = (Cdoc2KeySharesApiClient) o;
        return this.sharesApi.equals(that.sharesApi);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.sharesApi);
    }

    public static KeySharesClientBuilder builder() {
        return new KeySharesClientBuilder();
    }

    /**
     * @param keyShare key share data from openAPI
     * @return created share ID
     * @throws ApiException if Key share creation fails
     */
    public String createKeyShare(KeyShare keyShare) throws ApiException {
        Objects.requireNonNull(keyShare);
        Objects.requireNonNull(keyShare.getShare());
        Objects.requireNonNull(keyShare.getRecipient());

        ApiResponse<Void> response = sharesApi.createKeyShareWithHttpInfo(keyShare);

        return extractIdFromHeader(response, "KeyShare", "shareId", log);
    }

    /**
     * @param shareId key share ID
     * @param body    request body (optional)
     * @return created share nonce
     * @throws ApiException if Key share creation fails
     */
    public byte[] createNonce(String shareId, Optional<Object> body) throws ApiException {
        Objects.requireNonNull(shareId);

        NonceResponse response = sharesApi.createNonce(shareId, body.orElse(null));

        return response.getNonce();
    }

    /**
     * @param shareId key share ID
     * @param xAuthTicket Auth token
     * @return KeyShare key share
     * @throws ApiException if http response code is something else that 200
     */
    public KeyShare getKeyShare(String shareId, byte[] xAuthTicket) throws ApiException {
        if (shareId == null) {
            throw new IllegalArgumentException("shareId cannot be null");
        }

        try {
            return sharesApi.getKeyShareByShareId(shareId, xAuthTicket);
        } catch (ApiException ex) {
            log.error("Key share get request with share ID {} has failed with error code {}",
                shareId, ex.getCode());
            throw ex;
        }
    }

}
