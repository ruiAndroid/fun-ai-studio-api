package fun.ai.studio.config;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 与 Workspace 开发服务器（大机）workspace-node InternalAuthFilter 对齐的签名生成器。
 *
 * canonical:
 * method \n path \n query \n bodySha256Hex \n ts \n nonce
 */
public final class WorkspaceNodeProxySigner {

    private WorkspaceNodeProxySigner() {
    }

    public static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] dig = md.digest(bytes == null ? new byte[0] : bytes);
        StringBuilder sb = new StringBuilder(dig.length * 2);
        for (byte b : dig) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static String canonical(String method, String path, String query, String bodySha256Hex, long ts, String nonce) {
        String m = method == null ? "" : method;
        String p = path == null ? "" : path;
        String q = query == null ? "" : query;
        String b = bodySha256Hex == null ? sha256HexUnchecked(new byte[0]) : bodySha256Hex;
        String n = nonce == null ? "" : nonce;
        return m + "\n" + p + "\n" + q + "\n" + b + "\n" + ts + "\n" + n;
    }

    public static String hmacSha256Base64(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] out = mac.doFinal((data == null ? "" : data).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(out);
    }

    private static String sha256HexUnchecked(byte[] bytes) {
        try {
            return sha256Hex(bytes);
        } catch (Exception e) {
            // 理论上不会发生：SHA-256 一定存在
            throw new RuntimeException(e);
        }
    }
}


