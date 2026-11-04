package app.autopilot.ads.google;

import app.autopilot.ads.AdsException;

import com.google.ads.googleads.v26.errors.GoogleAdsError;
import com.google.ads.googleads.v26.errors.GoogleAdsException;
import com.google.ads.googleads.v26.errors.GoogleAdsFailure;
import com.google.api.gax.rpc.ApiException;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;

import java.util.Map;

/**
 * Turns Google failures into {@link AdsException}. The code is the name of the first error's code value
 * (for example {@code DUPLICATE_NAME}), the message adds the error family and Google's request id, and
 * {@code quotaRelated} is true for quota errors and RESOURCE_EXHAUSTED.
 */
final class AdsErrors {

    private AdsErrors() {}

    static AdsException translate(RuntimeException e) {
        if (e instanceof AdsException ads) {
            return ads;
        }
        if (e instanceof GoogleAdsException g) {
            return fromGoogleAds(g);
        }
        if (e instanceof ApiException api) {
            String code = api.getStatusCode().getCode().name();
            return new AdsException(code, "Google Ads call failed: " + code + ": " + api.getMessage(), isExhausted(code), e);
        }
        return new AdsException("UNEXPECTED_ERROR", "Google Ads call failed unexpectedly: " + e.getClass().getSimpleName()
                + ": " + e.getMessage(), false, e);
    }

    private static AdsException fromGoogleAds(GoogleAdsException g) {
        GoogleAdsFailure failure = g.getGoogleAdsFailure();
        String requestId = g.getRequestId();
        String suffix = requestId == null || requestId.isEmpty() ? "" : " (request_id=" + requestId + ")";
        String statusCode = g.getStatusCode().getCode().name();
        if (failure.getErrorsCount() == 0) {
            return new AdsException(statusCode, "Google Ads call failed: " + statusCode + ": " + g.getMessage() + suffix,
                    isExhausted(statusCode), g);
        }
        GoogleAdsError first = failure.getErrors(0);
        String family = "";
        String code = "UNKNOWN";
        for (Map.Entry<FieldDescriptor, Object> field : first.getErrorCode().getAllFields().entrySet()) {
            family = field.getKey().getName().toUpperCase(java.util.Locale.ROOT);
            code = field.getValue() instanceof EnumValueDescriptor v ? v.getName() : String.valueOf(field.getValue());
        }
        boolean quota = "QUOTA_ERROR".equals(family) || isExhausted(code) || isExhausted(statusCode);
        StringBuilder message = new StringBuilder("Google Ads rejected the request: ")
                .append(family).append('.').append(code).append(": ").append(first.getMessage());
        if (failure.getErrorsCount() > 1) {
            message.append(" (and ").append(failure.getErrorsCount() - 1).append(" more error")
                    .append(failure.getErrorsCount() > 2 ? "s" : "").append(')');
        }
        message.append(suffix);
        return new AdsException(code, message.toString(), quota, g);
    }

    private static boolean isExhausted(String code) {
        return "RESOURCE_EXHAUSTED".equals(code) || "RESOURCE_TEMPORARILY_EXHAUSTED".equals(code);
    }
}
