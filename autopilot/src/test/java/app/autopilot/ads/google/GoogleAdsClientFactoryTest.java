package app.autopilot.ads.google;

import app.autopilot.ads.AdsException;

import com.google.ads.googleads.v26.errors.GoogleAdsException;
import com.google.ads.googleads.v26.errors.GoogleAdsFailure;
import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.api.gax.rpc.ApiException;
import io.grpc.Metadata;
import io.grpc.Status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No client is built here: building one needs credentials. These tests only cover the argument handling. */
class GoogleAdsClientFactoryTest {

    @Test
    @DisplayName("[FACTORY] a developer token is required and the login customer id is parsed from digits with optional dashes")
    void arguments() {
        assertThatThrownBy(() -> new GoogleAdsClientFactory(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GoogleAdsClientFactory("  ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GoogleAdsClientFactory("token", "12ab")).isInstanceOf(IllegalArgumentException.class);

        assertThat(new GoogleAdsClientFactory("token", null).loginCustomerId()).isNull();
        assertThat(new GoogleAdsClientFactory("token", "").loginCustomerId()).isNull();
        assertThat(new GoogleAdsClientFactory("token", "123-456-7890").loginCustomerId()).isEqualTo(1234567890L);
        assertThat(GoogleAdsClientFactory.ADWORDS_SCOPE).isEqualTo("https://www.googleapis.com/auth/adwords");
    }

    @Test
    @DisplayName("[FACTORY] the developer token never shows up in toString or error messages")
    void tokenIsNotLeaked() {
        String secret = "s3cr3t-developer-token";
        GoogleAdsClientFactory factory = new GoogleAdsClientFactory(secret, "123-456-7890");

        assertThat(factory.toString()).doesNotContain(secret).contains("1234567890");
        assertThatThrownBy(() -> new GoogleAdsClientFactory(secret, "bad id"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(secret));
    }

    @Test
    @DisplayName("[GATEWAY] a GoogleAdsException without error details falls back to the gRPC status")
    void failureWithoutDetails() {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("request-id", Metadata.ASCII_STRING_MARSHALLER), "req-9");
        GoogleAdsException e = new GoogleAdsException(
                new ApiException(new RuntimeException("denied"), GrpcStatusCode.of(Status.Code.PERMISSION_DENIED), false),
                GoogleAdsFailure.getDefaultInstance(), headers);

        AdsException translated = AdsErrors.translate(e);

        assertThat(translated.code()).isEqualTo("PERMISSION_DENIED");
        assertThat(translated.quotaRelated()).isFalse();
        assertThat(translated.getMessage()).contains("request_id=req-9");

        AdsException exhausted = AdsErrors.translate(new GoogleAdsException(
                new ApiException(new RuntimeException("x"), GrpcStatusCode.of(Status.Code.RESOURCE_EXHAUSTED), false),
                GoogleAdsFailure.getDefaultInstance(), new Metadata()));
        assertThat(exhausted.quotaRelated()).isTrue();

        AdsException unexpected = AdsErrors.translate(new IllegalStateException("oops"));
        assertThat(unexpected.code()).isEqualTo("UNEXPECTED_ERROR");
        AdsException same = new AdsException("X", "y");
        assertThat(AdsErrors.translate(same)).isSameAs(same);
    }
}
