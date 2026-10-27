import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.HexFormat;

/** Signs the exact metadata bytes without exporting the app's private key. */
public final class SignUpdateManifest {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: SignUpdateManifest <json> <sig> <keystore> <alias>");
        String storeSecret = System.getenv("NAIWA_RELEASE_STORE_PASSWORD");
        String keySecret = System.getenv("NAIWA_RELEASE_KEY_PASSWORD");
        if (storeSecret == null || keySecret == null) throw new IllegalStateException("Signing passwords are not configured");
        char[] storePassword = storeSecret.toCharArray(), keyPassword = keySecret.toCharArray();
        try {
            Path metadata = Path.of(args[0]);
            Path output = Path.of(args[1]);
            Path keyStorePath = Path.of(args[2]);
            if (Files.exists(output) && (Files.isSameFile(output, metadata) || Files.isSameFile(output, keyStorePath)))
                throw new IllegalArgumentException("Signature output must not overwrite the metadata or keystore");
            KeyStore store = KeyStore.getInstance(keyStorePath.toFile(), storePassword);
            Key entry = store.getKey(args[3], keyPassword);
            Certificate certificate = store.getCertificate(args[3]);
            if (!(entry instanceof PrivateKey) || certificate == null) throw new IllegalArgumentException("Alias is not a signing key");
            String algorithm = switch (entry.getAlgorithm().toUpperCase()) {
                case "RSA" -> "SHA256withRSA";
                case "EC", "ECDSA" -> "SHA256withECDSA";
                default -> throw new IllegalArgumentException("Unsupported signing key type");
            };
            byte[] payload = Files.readAllBytes(metadata);
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign((PrivateKey) entry); signer.update(payload);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(certificate); verifier.update(payload);
            if (!verifier.verify(signature)) throw new IllegalStateException("Signature does not match the selected certificate");
            Files.write(output, signature);
            System.out.println("Certificate SHA-256: " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
        } finally { Arrays.fill(storePassword, '\0'); Arrays.fill(keyPassword, '\0'); }
    }
}
