package dev.errordetection.counter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class SourceKeyEncoder {

    /**
     * 검증된 source를 Redis hash tag에 안전한 고정 길이 SHA-256 값으로 변환합니다.
     *
     * @param source 에러 발생원
     * @return 소문자 16진수 SHA-256 문자열
     */
    public String encode(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
