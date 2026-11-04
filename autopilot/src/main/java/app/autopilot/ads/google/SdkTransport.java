package app.autopilot.ads.google;

import com.google.ads.googleads.lib.GoogleAdsClient;
import com.google.ads.googleads.lib.GoogleAdsResponseMetadata;
import com.google.ads.googleads.v26.services.ApplyRecommendationRequest;
import com.google.ads.googleads.v26.services.GoogleAdsRow;
import com.google.ads.googleads.v26.services.GoogleAdsServiceClient;
import com.google.ads.googleads.v26.services.MutateGoogleAdsRequest;
import com.google.ads.googleads.v26.services.RecommendationServiceClient;
import com.google.ads.googleads.v26.services.SearchGoogleAdsStreamRequest;
import com.google.ads.googleads.v26.services.SearchGoogleAdsStreamResponse;
import com.google.api.gax.grpc.GrpcCallContext;
import com.google.api.gax.rpc.ServerStream;

import java.util.List;

/**
 * Talks to Google Ads through the Java client library, v26. Service clients are created on first use,
 * so building the gateway does not open a connection.
 *
 * <p>The request id of a successful call is read with the library's own {@link GoogleAdsResponseMetadata},
 * which captures the {@code request-id} response header for that call only. Failed calls carry the id on
 * the exception ({@code GoogleAdsException.getRequestId()}).
 */
final class SdkTransport implements GoogleAdsTransport {

    private final GoogleAdsClient client;
    private GoogleAdsServiceClient googleAds;
    private RecommendationServiceClient recommendations;

    SdkTransport(GoogleAdsClient client) {
        this.client = client;
    }

    private synchronized GoogleAdsServiceClient googleAds() {
        if (googleAds == null) {
            googleAds = client.getVersion26().createGoogleAdsServiceClient();
        }
        return googleAds;
    }

    private synchronized RecommendationServiceClient recommendations() {
        if (recommendations == null) {
            recommendations = client.getVersion26().createRecommendationServiceClient();
        }
        return recommendations;
    }

    @Override
    public void searchStream(String customerId, String query, RowHandler handler) {
        SearchGoogleAdsStreamRequest request = SearchGoogleAdsStreamRequest.newBuilder()
                .setCustomerId(customerId)
                .setQuery(query)
                .build();
        ServerStream<SearchGoogleAdsStreamResponse> stream = googleAds().searchStreamCallable().call(request);
        try {
            for (SearchGoogleAdsStreamResponse batch : stream) {
                List<String> paths = batch.getFieldMask().getPathsList();
                for (GoogleAdsRow row : batch.getResultsList()) {
                    if (!handler.onRow(row, paths)) {
                        return;
                    }
                }
            }
        } finally {
            stream.cancel(); // harmless when the stream is already complete
        }
    }

    @Override
    public MutateOutcome mutate(MutateGoogleAdsRequest request) {
        GoogleAdsResponseMetadata metadata = new GoogleAdsResponseMetadata();
        GrpcCallContext context = metadata.addHandlers(GrpcCallContext.createDefault());
        var response = googleAds().mutateCallable().call(request, context);
        return new MutateOutcome(response, nullToEmpty(metadata.getRequestId()));
    }

    @Override
    public ApplyOutcome applyRecommendation(ApplyRecommendationRequest request) {
        GoogleAdsResponseMetadata metadata = new GoogleAdsResponseMetadata();
        GrpcCallContext context = metadata.addHandlers(GrpcCallContext.createDefault());
        var response = recommendations().applyRecommendationCallable().call(request, context);
        return new ApplyOutcome(response, nullToEmpty(metadata.getRequestId()));
    }

    @Override
    public synchronized void close() {
        if (googleAds != null) {
            googleAds.close();
            googleAds = null;
        }
        if (recommendations != null) {
            recommendations.close();
            recommendations = null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
