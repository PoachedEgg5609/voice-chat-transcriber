package cn.codex.voicetext;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import java.nio.charset.StandardCharsets;

public final class JnaEncodingRegressionTest {
	public static void main(String[] args) {
		String expected = "语音识别测试";
		VoskRuntime.prepareNativeStrings();
		byte[] utf8 = expected.getBytes(StandardCharsets.UTF_8);
		try (Memory nativeUtf8 = new Memory(utf8.length + 1L)) {
			nativeUtf8.write(0, utf8, 0, utf8.length);
			nativeUtf8.setByte(utf8.length, (byte) 0);
			String actual = nativeUtf8.getString(0);
			System.out.println("JNA encoding=" + Native.getDefaultStringEncoding());
			System.out.println("decoded=" + actual);
			if (!expected.equals(actual)) {
				throw new AssertionError("UTF-8 native text was corrupted: expected=" + expected + ", actual=" + actual);
			}
		}
	}
}
