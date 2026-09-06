package dev.errordetection.counter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceKeyEncoderTest {

    private final SourceKeyEncoder encoder = new SourceKeyEncoder();

    /**
     * 같은 source는 같은 고정 길이 키로 인코딩되는지 확인합니다.
     */
    @Test
    void encodesSourceDeterministically() {
        String first = encoder.encode("order-api");
        String second = encoder.encode("order-api");

        assertThat(first).hasSize(64).isEqualTo(second);
    }

    /**
     * 서로 다른 source가 같은 key scope를 공유하지 않는지 확인합니다.
     */
    @Test
    void separatesDifferentSources() {
        assertThat(encoder.encode("order-api")).isNotEqualTo(encoder.encode("payment-api"));
    }
}
