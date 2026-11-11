package kz.vainshtein.raftscore.backup;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
final class BundleAuthentication {
    private BundleAuthentication() {}
    static String sign(Properties manifest, String key) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            for (var name : new String[]{"formatVersion", "schemaVersion", "catalogVersion", "sizeBytes", "sha256"}) {
                var bytes = manifest.getProperty(name).getBytes(StandardCharsets.UTF_8);
                mac.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                mac.update(bytes);
            }
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    static boolean verify(Properties manifest, String key) {
        if (key.isBlank() || manifest.getProperty("hmacSha256") == null) return false;
        try { return MessageDigest.isEqual(HexFormat.of().parseHex(manifest.getProperty("hmacSha256")), HexFormat.of().parseHex(sign(manifest, key))); }
        catch (IllegalArgumentException | NullPointerException e) { return false; }
    }
}
