package cn.codex.voicetext;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public final class IflytekProtocolRegressionSuite {
	public static void main(String[] args) {
		testAuthentication();
		testDynamicCorrection();
		testErrorResponse();
		System.out.println("iFLYTEK protocol regression suite passed");
	}

	private static void testAuthentication() {
		URI signed = IflytekAuth.signedEndpoint(URI.create("wss://iat-api.xfyun.cn/v2/iat"),
				"key123", "secret456", Instant.parse("2019-07-10T07:35:43Z"));
		Map<String, String> query = query(signed.getRawQuery());
		require("iat-api.xfyun.cn".equals(query.get("host")), "signed host missing");
		require("Wed, 10 Jul 2019 07:35:43 GMT".equals(query.get("date")), "RFC1123 date mismatch");
		String authorization = new String(Base64.getDecoder().decode(query.get("authorization")), StandardCharsets.UTF_8);
		require(authorization.contains("api_key=\"key123\""), "API key missing from authorization");
		require(authorization.contains("headers=\"host date request-line\""), "signed headers mismatch");
		require(authorization.contains("signature=\"MaPnyiLIxv8Zd1YkB22kGA6uFgO99yV2agNMeyIz7zA=\""),
				"HMAC signature mismatch");

		URI firstOfMonth = IflytekAuth.signedEndpoint(URI.create("wss://iat-api.xfyun.cn/v2/iat"),
				"key123", "secret456", Instant.parse("2026-10-01T15:54:48Z"));
		require("Thu, 01 Oct 2026 15:54:48 GMT".equals(query(firstOfMonth.getRawQuery()).get("date")),
				"HTTP date must use a two-digit day of month");
	}

	private static void testDynamicCorrection() {
		IflytekResultAssembler result = new IflytekResultAssembler();
		result.accept(success(1, "apd", null, "我想玩", 1));
		result.accept(success(2, "apd", null, "我的世界", 1));
		require("我想玩我的世界".equals(result.text()), "append result mismatch");
		result.accept(success(3, "rpl", "[1,2]", "我想玩Minecraft", 1));
		require("我想玩Minecraft".equals(result.text()), "replacement result mismatch");
		require(result.accept("{\"code\":0,\"message\":\"success\",\"data\":{\"status\":2}}"),
				"final status was not detected");
	}

	private static void testErrorResponse() {
		try {
			new IflytekResultAssembler().accept("{\"code\":11200,\"message\":\"no permission\"}");
			throw new AssertionError("iFLYTEK error response was accepted");
		} catch (IllegalStateException expected) {
			require(expected.getMessage().contains("11200"), "error code not preserved");
		}
	}

	private static String success(int sequence, String mode, String range, String word, int status) {
		String rangePart = range == null ? "" : ",\"rg\":" + range;
		return "{\"code\":0,\"message\":\"success\",\"data\":{\"status\":" + status
				+ ",\"result\":{\"sn\":" + sequence + ",\"pgs\":\"" + mode + "\"" + rangePart
				+ ",\"ws\":[{\"cw\":[{\"w\":\"" + word + "\"},{\"w\":\"候选词不应拼接\"}]}]}}}";
	}

	private static Map<String, String> query(String rawQuery) {
		Map<String, String> values = new HashMap<>();
		for (String pair : rawQuery.split("&")) {
			int split = pair.indexOf('=');
			values.put(URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
					URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
		}
		return values;
	}

	private static void require(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
