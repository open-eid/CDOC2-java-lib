package ee.cyber.cdoc20.container;

import com.google.flatbuffers.FlatBufferBuilder;
import ee.cyber.cdoc20.crypto.ChaChaCipher;
import ee.cyber.cdoc20.crypto.Crypto;

import ee.cyber.cdoc20.crypto.ECKeys;
import ee.cyber.cdoc20.fbs.header.FMKEncryptionMethod;
import ee.cyber.cdoc20.fbs.header.Header;
import ee.cyber.cdoc20.fbs.header.PayloadEncryptionMethod;
import ee.cyber.cdoc20.fbs.header.RecipientRecord;
import ee.cyber.cdoc20.fbs.recipients.ECCPublicKey;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static ee.cyber.cdoc20.fbs.header.Details.*;

@SuppressWarnings("checkstyle:FinalClass")
public class Envelope {
    private static final Logger log = LoggerFactory.getLogger(Envelope.class);


    protected static final byte[] PRELUDE = {'C', 'D', 'O', 'C'};
    public static final byte VERSION = 2;

    public static final int MIN_HEADER_LEN = 1; //TODO: find out min header len


    private static final byte PAYLOAD_ENC_BYTE = PayloadEncryptionMethod.CHACHA20POLY1305;

    //private final byte[] fmkKeyBuf;
    private final Details.EccRecipient[] eccRecipients;

    private final SecretKey hmacKey;

    //content encryption  key
    private final SecretKey cekKey;


    private Envelope(Details.EccRecipient[] recipients, SecretKey hmacKey, SecretKey cekKey) {
        //this.fmkKeyBuf = fmkKey;
        this.eccRecipients = recipients;
        this.hmacKey = hmacKey;
        this.cekKey = cekKey;
    }

    private Envelope(Details.EccRecipient[] recipients, byte[] fmk) {
        this.eccRecipients = recipients;
        this.hmacKey = Crypto.deriveHeaderHmacKey(fmk);
        this.cekKey = Crypto.deriveContentEncryptionKey(fmk);
    }

    public static Envelope prepare(byte[] fmk, KeyPair senderEcKeyPair, List<ECPublicKey> recipients)
            throws NoSuchAlgorithmException, InvalidKeyException {

        log.trace("Envelope::prepare");
        if (fmk.length != Crypto.FMK_LEN_BYTES) {
            throw new IllegalArgumentException("Invalid FMK len");
        }

        List<Details.EccRecipient> eccRecipientList = new LinkedList<>();

        for (ECPublicKey otherPubKey: recipients) {
            byte[] kek = Crypto.deriveKeyEncryptionKey(senderEcKeyPair, otherPubKey, Crypto.CEK_LEN_BYTES);
            byte[] encryptedFmk = Crypto.xor(fmk, kek);
            Details.EccRecipient eccRecipient =
                    new Details.EccRecipient(otherPubKey, (ECPublicKey) senderEcKeyPair.getPublic(), encryptedFmk);
            log.debug("encrypted FMK: {}", HexFormat.of().formatHex(encryptedFmk));
            eccRecipientList.add(eccRecipient);
        }

        SecretKey hmacKey = Crypto.deriveHeaderHmacKey(fmk);
        SecretKey cekKey = Crypto.deriveContentEncryptionKey(fmk);
        return new Envelope(eccRecipientList.toArray(new Details.EccRecipient[0]), hmacKey, cekKey);
    }

    static List<Details.EccRecipient> parseHeader(InputStream envelopeIs, ByteArrayOutputStream outHeaderOs)
            throws IOException, CDocParseException, GeneralSecurityException {
        final int envelopeMinLen = PRELUDE.length
                + Byte.BYTES //version 0x02
                + Integer.BYTES //header length field
                + MIN_HEADER_LEN
                + Crypto.HHK_LEN_BYTES
                + 0; // TODO: payload min size

        if (envelopeIs.available() < envelopeMinLen) {
            throw new CDocParseException("not enough bytes to read, expected min of " + envelopeMinLen);
        }

        if (!Arrays.equals(PRELUDE, envelopeIs.readNBytes(PRELUDE.length))) {
            throw new CDocParseException("stream is not CDOC");
        }

        byte version = (byte) envelopeIs.read();
        if (VERSION != version) {
            throw new CDocParseException("Unsupported CDOC version " + version);
        }

        ByteBuffer headerLenBuf = ByteBuffer.wrap(envelopeIs.readNBytes(Integer.BYTES));
        headerLenBuf.order(ByteOrder.BIG_ENDIAN);
        int headerLen = headerLenBuf.getInt();

        if ((envelopeIs.available() < headerLen + Crypto.HHK_LEN_BYTES)
            || (headerLen < MIN_HEADER_LEN))  {
            throw new CDocParseException("invalid CDOC header length: " + headerLen);
        }

        byte[] headerBytes = envelopeIs.readNBytes(headerLen);

        if (outHeaderOs != null) {
            outHeaderOs.writeBytes(headerBytes);
        }
        Header header = deserializeHeader(headerBytes);

        return getDetailsEccRecipients(header);
    }

    private static List<Details.EccRecipient> getDetailsEccRecipients(Header header)
            throws CDocParseException, GeneralSecurityException {

        List<Details.EccRecipient> eccRecipientList = new LinkedList<>();
        for (int i = 0; i < header.recipientsLength(); i++) {
            RecipientRecord r = header.recipients(i);

            if (FMKEncryptionMethod.XOR != r.fmkEncryptionMethod()) {
                throw new CDocParseException("invalid FMK encryption method: " + r.fmkEncryptionMethod());
            }

            if (r.encryptedFmkLength() != Crypto.FMK_LEN_BYTES) {
                throw new CDocParseException("invalid FMK len: " + r.encryptedFmkLength());
            }

            ByteBuffer encryptedFmkBuf = r.encryptedFmkAsByteBuffer();
            byte[] encryptedFmkBytes = Arrays.copyOfRange(encryptedFmkBuf.array(),
                    encryptedFmkBuf.position(), encryptedFmkBuf.limit());

            log.debug("Parsed encrypted FMK: {}", HexFormat.of().formatHex(encryptedFmkBytes));

            if (r.detailsType() == recipients_ECCPublicKey) {
                ECCPublicKey detailsEccPublicKey = (ECCPublicKey) r.details(new ECCPublicKey());
                if (detailsEccPublicKey == null) {
                    throw new CDocParseException("error parsing Details");
                }

                try {
                    ECPublicKey recipientPubKey =
                            ECKeys.decodeEcPublicKeyFromTls(detailsEccPublicKey.recipientPublicKeyAsByteBuffer());
                    ECPublicKey senderPubKey =
                            ECKeys.decodeEcPublicKeyFromTls(detailsEccPublicKey.senderPublicKeyAsByteBuffer());

                    eccRecipientList.add(new Details.EccRecipient(r.fmkEncryptionMethod(),
                            recipientPubKey, senderPubKey, encryptedFmkBytes));
                } catch (IllegalArgumentException illegalArgumentException) {
                    throw new CDocParseException("illegal EC pub key encoding", illegalArgumentException);
                }
            } else if (r.detailsType() == recipients_KeyServer) {
                log.warn("Details.recipients_KeyServer not implemented");
            } else {
                log.error("Unknown Details type {}", r.detailsType());
                throw new CDocParseException("Unknown Details type " + r.detailsType());
            }
        }
        return eccRecipientList;
    }

    static Header deserializeHeader(byte[] buf) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(buf);
        return  Header.getRootAsHeader(byteBuffer);
    }

    public void encrypt(List<File> payloadFiles, OutputStream os) throws IOException, GeneralSecurityException {
        log.trace("encrypt");
        os.write(PRELUDE);
        os.write(new byte[]{VERSION});

        byte[] headerBytes = serializeHeader();

        ByteBuffer bb = ByteBuffer.allocate(Integer.BYTES);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.putInt(headerBytes.length);
        byte[] headerLenBytes = bb.array();

        os.write(headerLenBytes);
        os.write(headerBytes);


        byte[] hmac = Crypto.calcHmacSha256(hmacKey, headerBytes);
        os.write(hmac);
        byte[] additionalData = ChaChaCipher.getAdditionalData(headerBytes, hmac);
        try (CipherOutputStream cipherOutputStream =
                     ChaChaCipher.initChaChaOutputStream(os, cekKey, additionalData)) {

            //hidden feature, mainly for testing
            if (System.getProperties().containsKey("ee.cyber.cdoc20.disableCompression")) {
                if ((payloadFiles.size() == 1)
                        && (payloadFiles.get(0).getName().endsWith(".tgz")
                        || payloadFiles.get(0).getName().endsWith(".tar.gz"))) {

                    log.warn("disableCompression=true; Encrypting {} contents without compression",
                            payloadFiles.get(0));
                    try (FileInputStream fis = new FileInputStream(payloadFiles.get(0))) {
                        fis.transferTo(cipherOutputStream);
                    }
                    return;
                }
            }
            Tar.archiveFiles(cipherOutputStream, payloadFiles);
        }
    }

    private static List<ArchiveEntry> decrypt(InputStream cdocInputStream, KeyPair recipientEcKeyPair,
                                              Path outputDir, List<String> filesToExtract, boolean extract)
            throws GeneralSecurityException, IOException, CDocParseException {

        log.trace("Envelope::decrypt");
        log.debug("total available {}", cdocInputStream.available());
        ECPublicKey recipientPubKey = (ECPublicKey) recipientEcKeyPair.getPublic();
        ByteArrayOutputStream fileHeaderOs = new ByteArrayOutputStream();

        List<Details.EccRecipient> details = parseHeader(cdocInputStream, fileHeaderOs);

        for (Details.EccRecipient detailsEccRecipient : details) {
            ECPublicKey senderPubKey = detailsEccRecipient.getSenderPubKey();
            if (recipientPubKey.equals(detailsEccRecipient.getRecipientPubKey())) {
                byte[] kek = Crypto.deriveKeyDecryptionKey(recipientEcKeyPair, senderPubKey, Crypto.CEK_LEN_BYTES);
                byte[] fmk = Crypto.xor(kek, detailsEccRecipient.getEncryptedFileMasterKey());

                Envelope envelope = new Envelope(new Details.EccRecipient[]{detailsEccRecipient}, fmk);

                byte[] hmac = checkHmac(cdocInputStream, fileHeaderOs.toByteArray(), envelope.hmacKey);

                log.debug("payload available {}", cdocInputStream.available());

                byte[] additionalData = ChaChaCipher.getAdditionalData(fileHeaderOs.toByteArray(), hmac);
                try (CipherInputStream cis =
                             ChaChaCipher.initChaChaInputStream(cdocInputStream, envelope.cekKey, additionalData)) {

                    //hidden feature, mainly for testing
                    if (System.getProperties().containsKey("ee.cyber.cdoc20.disableCompression")
                            && System.getProperties().containsKey("ee.cyber.cdoc20.cDocFile")) {
                        log.warn("disableCompression=true; Decrypting only without decompressing");
                        return decryptTarGZip(outputDir, cis);
                    }

                    return Tar.processTarGz(cis, outputDir, filesToExtract, extract);
                }

            }
        }

        log.info("No matching EC pub key found");
        throw new CDocParseException("No matching EC pub key found");
    }

    /**
     * Check that hmac read from cdocInputStream and hmac calculated from headerBytes match
     * @param cdocInputStream
     * @param headerBytes
     * @param hmacKey
     * @return
     * @throws IOException
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     * @throws CDocParseException if hmacs don't match
     */
    private static byte[] checkHmac(InputStream cdocInputStream, byte[] headerBytes, SecretKey hmacKey)
            throws IOException, NoSuchAlgorithmException, InvalidKeyException, CDocParseException {
        byte[] hmac;
        if (cdocInputStream.available() > Crypto.HHK_LEN_BYTES) {
            byte[] calculatedHmac = Crypto.calcHmacSha256(hmacKey, headerBytes);
            hmac = cdocInputStream.readNBytes(Crypto.HHK_LEN_BYTES);

            if (!Arrays.equals(calculatedHmac, hmac)) {
                log.debug("calc hmac: {}", HexFormat.of().formatHex(calculatedHmac));
                log.debug("file hmac: {}", HexFormat.of().formatHex(hmac));

                throw new CDocParseException("Invalid hmac");
            }
        } else {
            throw new CDocParseException("No hmac");
        }
        return hmac;
    }

    /* Decrypt contents of cis and copy its contents into .tgz file under outputDir*/
    private static List<ArchiveEntry> decryptTarGZip(Path outputDir, CipherInputStream cis) throws IOException {

        String cDocFileName = System.getProperty("ee.cyber.cdoc20.cDocFile");
        if ((cDocFileName == null) || cDocFileName.isEmpty()) {
            throw new IllegalStateException("Property \"ee.cyber.cdoc20.cDocFile\" not defined.");
        }

        if (cDocFileName.endsWith(".cdoc")) {
            cDocFileName = cDocFileName.substring(0, cDocFileName.length() - ".cdoc".length());
        }

        File tarGzFile = outputDir.resolve(cDocFileName + ".tgz").toFile();
        log.debug("Decrypting {} to {}", cDocFileName, tarGzFile);
        try (FileOutputStream fos = new FileOutputStream(tarGzFile)) {
            long transferred = 0;
            byte[] buffer = new byte[8192];
            int read;
            int megaBytes = 0;
            while ((read = cis.read(buffer, 0, 8192)) >= 0) {
                fos.write(buffer, 0, read);
                transferred += read;
                if ((transferred > (megaBytes + 1) * (1024 * 1024))) {
                    megaBytes += 1;
                    if ((megaBytes % 10) == 0) {
                        System.out.print("*");
                    } else {
                        System.out.print(".");
                    }
                    if ((megaBytes % 100) == 0) {
                        System.out.println(" " + megaBytes);
                    }

                    System.out.flush();
                }
            }

            final long fileSize = transferred;

            //CHECKSTYLE:OFF
            return List.of(new ArchiveEntry() {
                @Override
                public String getName() { return tarGzFile.getName(); }

                @Override
                public long getSize() { return fileSize; }

                @Override
                public boolean isDirectory() { return false; }

                @Override
                public Date getLastModifiedDate() {return Date.from(Instant.now()); }
            });
            //CHECKSTYLE:ON
        }
    }

    /**
     * Decrypt and extract all files from cdocInputStream
     * @param cdocInputStream
     * @param recipientEcKeyPair
     * @param outputDir
     * @return
     * @throws GeneralSecurityException
     * @throws IOException
     * @throws CDocParseException
     */
    public static List<String> decrypt(InputStream cdocInputStream, KeyPair recipientEcKeyPair, Path outputDir)
            throws GeneralSecurityException, IOException, CDocParseException {
        return decrypt(cdocInputStream, recipientEcKeyPair, outputDir, null, true)
                .stream()
                .map(ArchiveEntry::getName)
                .collect(Collectors.toList());
    }

    public static List<String> decrypt(InputStream cdocInputStream, KeyPair recipientEcKeyPair, Path outputDir,
                                       List<String> filesToExtract)
            throws GeneralSecurityException, IOException, CDocParseException {
        return decrypt(cdocInputStream, recipientEcKeyPair, outputDir, filesToExtract, true)
                .stream()
                .map(ArchiveEntry::getName)
                .collect(Collectors.toList());
    }




    public static List<ArchiveEntry> list(InputStream cdocInputStream, KeyPair recipientEcKeyPair)
            throws GeneralSecurityException, IOException, CDocParseException {
        return decrypt(cdocInputStream, recipientEcKeyPair, null, null, false);
    }

    byte[] serializeHeader() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializeHeader(baos);
        return baos.toByteArray();
    }

    void serializeHeader(OutputStream os) throws IOException {
        FlatBufferBuilder builder = new FlatBufferBuilder(1024);
        int[] recipients = new int[eccRecipients.length];

        for (int i = 0; i < eccRecipients.length; i++) {
            Details.EccRecipient eccRecipient = eccRecipients[i];

            int recipientPubKeyOffset = builder.createByteVector(eccRecipient.getRecipientPubKeyTlsEncoded());
            int senderPubKeyOffset = builder.createByteVector(eccRecipient.getSenderPubKeyTlsEncoded());
            int eccPubKeyOffset = ECCPublicKey.createECCPublicKey(builder,
                    eccRecipient.ellipticCurve,
                    recipientPubKeyOffset,
                    senderPubKeyOffset
            );

            int encFmkOffset =
                    RecipientRecord.createEncryptedFmkVector(builder, eccRecipient.getEncryptedFileMasterKey());

            RecipientRecord.startRecipientRecord(builder);
            RecipientRecord.addDetailsType(builder, ee.cyber.cdoc20.fbs.header.Details.recipients_ECCPublicKey);
            RecipientRecord.addDetails(builder, eccPubKeyOffset);

            RecipientRecord.addEncryptedFmk(builder, encFmkOffset);
            RecipientRecord.addFmkEncryptionMethod(builder, FMKEncryptionMethod.XOR);

            int recipientOffset = RecipientRecord.endRecipientRecord(builder);

            recipients[i] = recipientOffset;
        }

        int recipientsOffset = Header.createRecipientsVector(builder, recipients);

        Header.startHeader(builder);
        Header.addRecipients(builder, recipientsOffset);
        Header.addPayloadEncryptionMethod(builder, PAYLOAD_ENC_BYTE);
        int headerOffset = Header.endHeader(builder);
        Header.finishHeaderBuffer(builder, headerOffset);

        ByteBuffer buf = builder.dataBuffer();
        int bufLen = buf.limit() - buf.position();
        os.write(buf.array(), buf.position(), bufLen);
    }

    //CHECKSTYLE:OFF - generated code
    @SuppressWarnings("java:S3776")
    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof Envelope)) {
            return false;
        } else {
            Envelope other = (Envelope)o;
            if (!other.canEqual(this)) {
                return false;
            } else if (!Arrays.deepEquals(this.eccRecipients, other.eccRecipients)) {
                return false;
            } else {
                Object this$hmacKey = this.hmacKey;
                Object other$hmacKey = other.hmacKey;
                if (this$hmacKey == null) {
                    if (other$hmacKey != null) {
                        return false;
                    }
                } else if (!this$hmacKey.equals(other$hmacKey)) {
                    return false;
                }

                Object this$cekKey = this.cekKey;
                Object other$cekKey = other.cekKey;
                if (this$cekKey == null) {
                    if (other$cekKey != null) {
                        return false;
                    }
                } else if (!this$cekKey.equals(other$cekKey)) {
                    return false;
                }

                return true;
            }
        }
    }

    protected boolean canEqual(Object other) {
        return other instanceof Envelope;
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = result * 59 + Arrays.deepHashCode(this.eccRecipients);
        Object $hmacKey = this.hmacKey;
        result = result * 59 + ($hmacKey == null ? 43 : $hmacKey.hashCode());
        Object $cekKey = this.cekKey;
        result = result * 59 + ($cekKey == null ? 43 : $cekKey.hashCode());
        return result;
    }
    //CHECKSTYLE:ON
}
