package app.autopilot.ads.google;

import com.google.ads.googleads.lib.GoogleAdsClient;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.IOException;
import java.util.List;

/**
 * Builds the {@link GoogleAdsClient} from plain constructor arguments, so a Spring configuration class can
 * pass them from properties.
 *
 * <p>Credentials are Application Default Credentials only: on the cluster that is Workload Identity, on a
 * laptop {@code gcloud auth application-default login}. There are no key files and no refresh tokens in
 * this code. The credentials are scoped to the Google Ads scope. Nothing here logs the developer token.
 */
public final class GoogleAdsClientFactory {

    static final String ADWORDS_SCOPE = "https://www.googleapis.com/auth/adwords";

    private final String developerToken;
    private final Long loginCustomerId;

    /**
     * @param developerToken the Google Ads API developer token
     * @param loginCustomerId manager (MCC) account id when the customer is accessed through one; null or blank otherwise.
     *        Dashes are accepted ("123-456-7890").
     */
    public GoogleAdsClientFactory(String developerToken, String loginCustomerId) {
        if (developerToken == null || developerToken.isBlank()) {
            throw new IllegalArgumentException("developer token is required");
        }
        this.developerToken = developerToken.strip();
        this.loginCustomerId = parseLoginCustomerId(loginCustomerId);
    }

    /** Null for no login customer; otherwise the id as a number. */
    static Long parseLoginCustomerId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replace("-", "").strip();
        if (!digits.matches("\\d{1,15}")) {
            throw new IllegalArgumentException("login customer id must be digits (dashes allowed)");
        }
        return Long.valueOf(digits);
    }

    public Long loginCustomerId() {
        return loginCustomerId;
    }

    /**
     * Creates the client. Does not call Google; the first request fetches an access token with the
     * application default credentials.
     *
     * @throws IOException when no application default credentials can be found
     */
    public GoogleAdsClient create() throws IOException {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault().createScoped(List.of(ADWORDS_SCOPE));
        GoogleAdsClient.Builder builder = GoogleAdsClient.newBuilder()
                .setCredentials(credentials)
                .setDeveloperToken(developerToken);
        if (loginCustomerId != null) {
            builder.setLoginCustomerId(loginCustomerId);
        }
        return builder.build();
    }

    @Override
    public String toString() {
        return "GoogleAdsClientFactory[loginCustomerId=" + loginCustomerId + "]"; // never the token
    }
}
