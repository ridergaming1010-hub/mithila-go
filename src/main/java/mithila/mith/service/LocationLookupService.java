package mithila.mith.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class LocationLookupService {
    private static final long MIN_REQUEST_INTERVAL_MILLIS = 1_100;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @Value("${mithila.geocoding.nominatim-url:https://nominatim.openstreetmap.org}")
    private String nominatimUrl;
    @Value("${mithila.geocoding.user-agent:MithilaGo/1.0}")
    private String userAgent;
    private long lastRequestAt;

    public LocationLookupService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public LocationResult reverse(double latitude, double longitude) {
        validateCoordinates(latitude, longitude);
        String query = "/reverse?format=jsonv2&addressdetails=1&zoom=18&lat=" + latitude + "&lon=" + longitude;
        JsonNode result = request(query);
        JsonNode address = result.path("address");
        String namedPlace = firstText(result, "name", "amenity", "shop", "building");
        String street = firstText(address, "road", "pedestrian", "footway", "residential", "path");
        String houseNumber = text(address, "house_number");
        String locality = firstText(address, "neighbourhood", "suburb", "city_district", "village",
                "town", "city", "municipality", "county");
        String label = namedPlace.isBlank() ? joinLocation(houseNumber, street, locality)
                : joinNamedPlace(namedPlace, street, locality);
        if (label.isBlank()) label = text(result, "display_name");
        if (label.isBlank()) {
            throw unavailable("The map service could not identify this location.");
        }
        return new LocationResult(label, locality, latitude, longitude);
    }

    private String joinNamedPlace(String namedPlace, String street, String locality) {
        if (street.isBlank() && locality.isBlank()) return namedPlace;
        if (locality.isBlank()) return namedPlace + ", " + street;
        if (street.isBlank()) return namedPlace + ", " + locality;
        return namedPlace + ", " + street + ", " + locality;
    }

    public List<LocationResult> search(String query, Double latitude, Double longitude) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.length() < 3) return List.of();
        if (normalizedQuery.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Location search must be 120 characters or fewer.");
        }
        if ((latitude == null) != (longitude == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide both map coordinates for nearby search.");
        }
        if (latitude != null) validateCoordinates(latitude, longitude);

        StringBuilder path = new StringBuilder("/search?format=jsonv2&addressdetails=1&limit=6&countrycodes=in&q=")
                .append(URLEncoder.encode(normalizedQuery, StandardCharsets.UTF_8));
        if (latitude != null) {
            path.append("&viewbox=").append(longitude - 0.2).append(',')
                    .append(latitude + 0.2).append(',')
                    .append(longitude + 0.2).append(',')
                    .append(latitude - 0.2).append("&bounded=1");
        }

        JsonNode results = request(path.toString());
        if (!results.isArray()) throw unavailable("The map service returned invalid location suggestions.");
        List<LocationResult> locations = new ArrayList<>();
        for (JsonNode result : results) {
            String name = text(result, "name");
            String displayName = text(result, "display_name");
            if (name.isBlank()) name = displayName.contains(",")
                    ? displayName.substring(0, displayName.indexOf(',')).trim() : displayName;
            double resultLatitude = number(result, "lat");
            double resultLongitude = number(result, "lon");
            if (name.isBlank() || !validCoordinates(resultLatitude, resultLongitude)) continue;
            String area = firstText(result.path("address"), "neighbourhood", "suburb", "city_district",
                    "village", "town", "city", "municipality", "county", "state");
            locations.add(new LocationResult(name, area, resultLatitude, resultLongitude));
        }
        return List.copyOf(locations);
    }

    private synchronized JsonNode request(String path) {
        long waitMillis = MIN_REQUEST_INTERVAL_MILLIS - (System.currentTimeMillis() - lastRequestAt);
        if (waitMillis > 0) {
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw unavailable("Location lookup was interrupted. Please try again.");
            }
        }
        lastRequestAt = System.currentTimeMillis();
        HttpRequest request = HttpRequest.newBuilder(URI.create(nominatimUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", userAgent)
                .header("Accept-Language", Locale.getDefault().toLanguageTag())
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw unavailable("The map service could not look up this location. Please try again.");
            }
            return mapper.readTree(response.body());
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Location lookup is temporarily unavailable. Choose a known place or map pin and try again.", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Location lookup was interrupted. Please try again.", error);
        }
    }

    private String joinLocation(String houseNumber, String street, String locality) {
        String address = houseNumber.isBlank() ? street : street.isBlank() ? houseNumber : houseNumber + " " + street;
        if (address.isBlank()) return locality;
        return locality.isBlank() || locality.equalsIgnoreCase(address) ? address : address + ", " + locality;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asString().trim() : "";
    }

    private double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        try {
            return value.isNumber() ? value.asDouble() : Double.parseDouble(value.asText());
        } catch (NumberFormatException error) {
            return Double.NaN;
        }
    }

    private void validateCoordinates(double latitude, double longitude) {
        if (!validCoordinates(latitude, longitude)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter valid latitude and longitude coordinates.");
        }
    }

    private boolean validCoordinates(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    private ResponseStatusException unavailable(String message) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public record LocationResult(String name, String area, double latitude, double longitude) { }
}
