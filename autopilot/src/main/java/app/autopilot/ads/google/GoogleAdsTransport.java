package app.autopilot.ads.google;

import com.google.ads.googleads.v26.services.ApplyRecommendationRequest;
import com.google.ads.googleads.v26.services.ApplyRecommendationResponse;
import com.google.ads.googleads.v26.services.GoogleAdsRow;
import com.google.ads.googleads.v26.services.MutateGoogleAdsRequest;
import com.google.ads.googleads.v26.services.MutateGoogleAdsResponse;

import java.util.List;

/**
 * The three calls the gateway makes to Google Ads. The real implementation wraps the client library; tests
 * supply a fake, so the gateway logic runs without a network.
 */
interface GoogleAdsTransport extends AutoCloseable {

    /** Receives rows of one SearchStream. */
    interface RowHandler {
        /**
         * @param fieldPaths the selected field paths of the query (for example {@code campaign.id}), in select order
         * @return false to stop reading; the stream is then cancelled
         */
        boolean onRow(GoogleAdsRow row, List<String> fieldPaths);
    }

    /** One SearchStream request (one operation for quota, however many batches come back). */
    void searchStream(String customerId, String query, RowHandler handler);

    MutateOutcome mutate(MutateGoogleAdsRequest request);

    ApplyOutcome applyRecommendation(ApplyRecommendationRequest request);

    /** @param requestId Google's request id from the response headers; empty when Google did not send one */
    record MutateOutcome(MutateGoogleAdsResponse response, String requestId) {}

    record ApplyOutcome(ApplyRecommendationResponse response, String requestId) {}

    @Override
    void close();
}
