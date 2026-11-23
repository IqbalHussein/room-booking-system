package com.kshitij.roombooking.service;

import com.kshitij.roombooking.model.BookingStage;
import com.kshitij.roombooking.model.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Where the profiles to book for come from.
 *
 * <p>
 * With {@code BOOKING_CONFIG_URL} and {@code BOOKING_SYNC_TOKEN} set, profiles
 * are fetched from the scheduler web app's {@code /api/export}. Without them,
 * a single profile is built from {@code BOOKING_USERNAME}/{@code BOOKING_PASSWORD}
 * and the bundled {@code default-schedule.json}, so a plain local run still works.
 */
@Component
public class ScheduleSource {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleSource.class);

    public List<Profile> load() throws BookingException {
        String url = System.getenv("BOOKING_CONFIG_URL");
        if (url == null || url.isBlank()) {
            logger.info("BOOKING_CONFIG_URL not set; using the bundled default schedule.");
            return List.of(new Profile("default", "default",
                    System.getenv("BOOKING_USERNAME"), System.getenv("BOOKING_PASSWORD"),
                    BookingSchedule.defaultSchedule()));
        }
        String token = System.getenv("BOOKING_SYNC_TOKEN");
        if (token == null || token.isBlank()) {
            throw new BookingException(BookingStage.CONFIG,
                    "BOOKING_CONFIG_URL is set but BOOKING_SYNC_TOKEN is not.",
                    "Add the sync token as a GitHub Actions secret and pass it to the container.");
        }
        return BookingSchedule.parseProfiles(fetch(exportUrl(url), token));
    }

    static String exportUrl(String base) {
        String trimmed = base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/api/export";
    }

    private String fetch(String url, String token) throws BookingException {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                String body = response.body() == null ? "" : response.body();
                throw new BookingException(BookingStage.CONFIG,
                        "Schedule export returned HTTP " + response.statusCode() + ".",
                        "url: " + url + " | body: " + body.substring(0, Math.min(200, body.length())));
            }
            return response.body();
        } catch (BookingException e) {
            throw e;
        } catch (Exception e) {
            throw new BookingException(BookingStage.CONFIG,
                    "Could not fetch the schedule export: " + e.getMessage(), "url: " + url, e);
        }
    }
}
