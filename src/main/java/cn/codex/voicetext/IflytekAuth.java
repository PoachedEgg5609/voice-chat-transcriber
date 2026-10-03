package cn.codex.voicetext;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Builds the signed WebSocket URL required by iFLYTEK streaming IAT. */
public final class IflytekAuth {
	private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
			.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
			.withZone(ZoneOffset.UTC);

	private IflytekAuth() {}

	public static URI signedEndpoint(URI endpoint, String apiKey, String apiSecret, Instant now) {
		if (endpoint == null || endpoint.getHost() == null || endpoint.getRawPath() == null) {
			throw new IllegalArgumentException("讯飞接口地址无效");
		}
		if (!"wss".equalsIgnoreCase(endpoint.getScheme())) {
			throw new IllegalArgumentException("讯飞接口必须使用 WSS");
		}
		if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
			throw new IllegalArgumentException("讯飞 APIKey 或 APISecret 未填写");
		}
		String date = HTTP_DATE.format(now);
		String signatureOrigin = "host: " + endpoint.getHost() + "\n"
				+ "date: " + date + "\n"
				+ "GET " + endpoint.getRawPath() + " HTTP/1.1";
		String signature = hmacSha256(apiSecret, signatureOrigin);
		String authorization = "api_key=\"" + apiKey + "\", algorithm=\"hmac-sha256\", "
				+ "headers=\"host date request-line\", signature=\"" + signature + "\"";
		String query = "authorization=" + encode(Base64.getEncoder().encodeToString(
				authorization.getBytes(StandardCharsets.UTF_8)))
				+ "&date=" + encode(date) + "&host=" + encode(endpoint.getHost());
		return URI.create(endpoint.getScheme() + "://" + endpoint.getRawAuthority()
				+ endpoint.getRawPath() + "?" + query);
	}

	private static String hmacSha256(String secret, String value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException error) {
			throw new IllegalStateException("当前 Java 环境不支持 HMAC-SHA256", error);
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
