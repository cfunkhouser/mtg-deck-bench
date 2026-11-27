package mtgbench.fetch;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.IntFunction;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** GETs JSON documents, asking for gzip, and turns failures into messages for the user. */
final class JsonHttp {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String site;
    private final String userAgent;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** @param site the service's name, for messages ("Archidekt") */
    JsonHttp(String site, String userAgent) {
        this.site = site;
        this.userAgent = userAgent;
    }

    /**
     * @param notFound the message for a 401, 403 or 404, given the status
     * @throws FetchException if the request fails, the status isn't 200, or the body isn't JSON
     */
    JsonNode get(URI uri, IntFunction<String> notFound) throws FetchException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .header("User-Agent", userAgent)
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new FetchException("Cannot reach " + site + " (" + uri + "): " + describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("Interrupted while downloading " + uri);
        }
        int status = response.statusCode();
        if (status == 404 || status == 403 || status == 401) {
            throw new FetchException(notFound.apply(status));
        }
        if (status != 200) {
            throw new FetchException(site + " returned HTTP " + status + " for " + uri + "; try again later.");
        }
        try (InputStream body = decode(response)) {
            JsonNode node = JSON.readTree(body);
            if (node == null || node.isMissingNode()) {
                throw new FetchException(site + " sent an empty answer for " + uri);
            }
            return node;
        } catch (IOException e) {
            throw new FetchException(site + "'s answer for " + uri + " isn't valid JSON: " + describe(e));
        }
    }

    private static InputStream decode(HttpResponse<byte[]> response) throws IOException {
        InputStream raw = new ByteArrayInputStream(response.body());
        boolean gzip = response.headers().firstValue("Content-Encoding").map(v -> v.equalsIgnoreCase("gzip"))
                .orElse(false);
        return gzip ? new GZIPInputStream(raw) : raw;
    }

    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
