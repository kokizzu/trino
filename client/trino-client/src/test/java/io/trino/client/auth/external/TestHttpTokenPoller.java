/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.client.auth.external;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.junit5.StartStop;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;

import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.google.common.net.MediaType.JSON_UTF_8;
import static java.lang.String.format;
import static java.net.HttpURLConnection.HTTP_GONE;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static java.net.URI.create;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD;

@TestInstance(PER_METHOD)
public class TestHttpTokenPoller
{
    private static final String TOKEN_PATH = "/v1/authentications/sso/test/token";
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    private static final TokenPoller tokenPoller = new HttpTokenPoller(new OkHttpClient.Builder()
                .callTimeout(Duration.ofMillis(500))
                .build());

    @StartStop
    private final MockWebServer server = new MockWebServer();

    @Test
    public void testTokenReady()
    {
        server.enqueue(statusAndBody(HTTP_OK, jsonPair("token", "token")));

        TokenPollResult result = tokenPoller.pollForToken(tokenUri(), ONE_SECOND);

        assertThat(result.getToken().token()).isEqualTo("token");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    public void testTokenNotReady()
    {
        server.enqueue(statusAndBody(HTTP_OK, jsonPair("nextUri", tokenUri())));

        TokenPollResult result = tokenPoller.pollForToken(tokenUri(), ONE_SECOND);

        assertThat(result.isPending()).isTrue();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    public void testErrorResponse()
    {
        server.enqueue(statusAndBody(HTTP_OK, jsonPair("error", "test failure")));

        TokenPollResult result = tokenPoller.pollForToken(tokenUri(), ONE_SECOND);

        assertThat(result.isFailed()).isTrue();
        assertThat(result.getError()).contains("test failure");
    }

    @Test
    public void testBadHttpStatus()
    {
        server.enqueue(new MockResponse.Builder().code(HTTP_GONE).build());

        TokenPollResult result = tokenPoller.pollForToken(tokenUri(), ONE_SECOND);

        assertThat(result.isFailed()).isTrue();
        assertThat(result.getError())
                .matches("Request to http://.* failed: JsonResponse\\{statusCode=410, .*");
    }

    @Test
    public void testInvalidJsonBody()
    {
        server.enqueue(statusAndBody(HTTP_OK, jsonPair("foo", "bar")));

        TokenPollResult result = tokenPoller.pollForToken(tokenUri(), ONE_SECOND);

        assertThat(result.isFailed()).isTrue();
        assertThat(result.getError())
                .isEqualTo("Failed to poll for token. No fields set in response.");
    }

    @Test
    public void testInvalidNextUri()
    {
        server.enqueue(statusAndBody(HTTP_OK, jsonPair("nextUri", ":::")));

        TokenPollResult result = tokenPoller.pollForToken(tokenUri(), ONE_SECOND);

        assertThat(result.isFailed()).isTrue();
        assertThat(result.getError())
                .matches("Request to http://.* failed: JsonResponse\\{statusCode=200, .*, hasValue=false} .*");
    }

    @Test
    public void testHttpStatus503()
    {
        for (int i = 1; i <= 100; i++) {
            server.enqueue(statusAndBody(HTTP_UNAVAILABLE, "Server failure #" + i));
        }

        assertThatThrownBy(() -> tokenPoller.pollForToken(tokenUri(), ONE_SECOND))
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseExactlyInstanceOf(IOException.class)
                .hasMessageFindingMatch("Request to http://.* failed: JsonResponse\\{statusCode=503,")
                .hasMessageContaining("[Error: Server failure #");

        assertThat(server.getRequestCount()).isGreaterThan(1);
    }

    @Test
    public void testHttpTimeout()
    {
        // force request to timeout by not enqueuing response

        assertThatThrownBy(() -> tokenPoller.pollForToken(tokenUri(), ONE_SECOND))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageEndingWith(": timeout");
    }

    @Test
    public void testTokenReceived()
            throws InterruptedException
    {
        server.enqueue(status(HTTP_OK));

        tokenPoller.tokenReceived(tokenUri());

        RecordedRequest request = server.takeRequest(1, MILLISECONDS);
        assertThat(request.getMethod()).isEqualTo("DELETE");
        assertThat(request.getUrl()).isEqualTo(HttpUrl.get(tokenUri()));
    }

    @Test
    public void testTokenReceivedRetriesUntilNotErrorReturned()
    {
        server.enqueue(status(HTTP_UNAVAILABLE));
        server.enqueue(status(HTTP_UNAVAILABLE));
        server.enqueue(status(HTTP_UNAVAILABLE));
        server.enqueue(status(202));

        tokenPoller.tokenReceived(tokenUri());

        assertThat(server.getRequestCount()).isEqualTo(4);
    }

    @Test
    public void testTokenReceivedDoesNotRetriesIndefinitely()
    {
        for (int i = 1; i <= 100; i++) {
            server.enqueue(status(HTTP_UNAVAILABLE));
        }

        tokenPoller.tokenReceived(tokenUri());

        assertThat(server.getRequestCount()).isLessThan(100);
    }

    private URI tokenUri()
    {
        return create("http://" + server.getHostName() + ":" + server.getPort() + TOKEN_PATH);
    }

    private static String jsonPair(String key, Object value)
    {
        return format("{\"%s\": \"%s\"}", key, value);
    }

    private static MockResponse statusAndBody(int status, String body)
    {
        return new MockResponse.Builder()
                .code(status)
                .addHeader(CONTENT_TYPE, JSON_UTF_8)
                .body(body)
                .build();
    }

    private static MockResponse status(int status)
    {
        return new MockResponse.Builder()
                .code(status)
                .build();
    }
}
