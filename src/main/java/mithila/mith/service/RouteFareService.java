package mithila.mith.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RouteFareService {
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @Value("${mithila.routing.ors-api-key:}") private String orsApiKey;
    @Value("${mithila.routing.osrm-url:https://router.project-osrm.org}") private String osrmUrl;
    @Value("${mithila.fares.bike.base:0}") private double bikeBase;
    @Value("${mithila.fares.bike.rate-per-km:12}") private double bikeRate;
    @Value("${mithila.fares.bike.minimum:30}") private int bikeMinimum;
    @Value("${mithila.fares.auto.base:0}") private double autoBase;
    @Value("${mithila.fares.auto.rate-per-km:20}") private double autoRate;
    @Value("${mithila.fares.auto.minimum:30}") private int autoMinimum;
    @Value("${mithila.fares.erickshaw.base:0}") private double erickshawBase;
    @Value("${mithila.fares.erickshaw.rate-per-km:15}") private double erickshawRate;
    @Value("${mithila.fares.erickshaw.minimum:30}") private int erickshawMinimum;
    @Value("${mithila.fares.mini.base:0}") private double miniBase;
    @Value("${mithila.fares.mini.rate-per-km:20}") private double miniRate;
    @Value("${mithila.fares.mini.minimum:50}") private int miniMinimum;
    @Value("${mithila.fares.suv.base:0}") private double suvBase;
    @Value("${mithila.fares.suv.rate-per-km:25}") private double suvRate;
    @Value("${mithila.fares.suv.minimum:80}") private int suvMinimum;

    public RouteFareService(ObjectMapper mapper) { this.mapper = mapper; }

    public FareQuote quote(String vehicleType, Double pickupLatitude, Double pickupLongitude,
                           Double dropoffLatitude, Double dropoffLongitude) {
        Rate rate = rateFor(vehicleType);
        if (pickupLatitude == null && pickupLongitude == null
                && dropoffLatitude == null && dropoffLongitude == null) {
            return new FareQuote(rate.minimum(), 0, 0, true, List.of());
        }
        if (!validPair(pickupLatitude, pickupLongitude) || !validPair(dropoffLatitude, dropoffLongitude)) {
            throw new IllegalArgumentException("Select pickup and destination locations on the map.");
        }
        try {
            return roadQuote(rate, pickupLatitude, pickupLongitude, dropoffLatitude, dropoffLongitude);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Road routing was interrupted. Please try again; your ride has not been booked.", error);
        } catch (IOException | IllegalStateException | IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "A road route is unavailable right now. Please try again; your ride has not been booked.", error);
        }
    }

    private FareQuote roadQuote(Rate rate, double fromLat, double fromLon, double toLat, double toLon)
            throws IOException, InterruptedException {
        String body = "{\"coordinates\":[[" + fromLon + "," + fromLat + "],[" + toLon + "," + toLat + "]],"
                + "\"instructions\":false,\"geometry\":true}";
        HttpRequest.Builder builder;
        if (orsApiKey != null && !orsApiKey.isBlank()) {
            builder = HttpRequest.newBuilder(URI.create("https://api.openrouteservice.org/v2/directions/driving-car/geojson"))
                    .header("Authorization", orsApiKey.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            String url = osrmUrl.replaceAll("/+$", "") + "/route/v1/driving/" + fromLon + "," + fromLat + ";"
                    + toLon + "," + toLat + "?overview=full&geometries=geojson&steps=false";
            builder = HttpRequest.newBuilder(URI.create(url)).GET();
        }
        HttpResponse<String> response = http.send(builder.timeout(Duration.ofSeconds(4)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Routing failed");
        JsonNode root = mapper.readTree(response.body());
        JsonNode summary;
        JsonNode geometry;
        if (orsApiKey != null && !orsApiKey.isBlank()) {
            JsonNode feature = root.path("features").path(0);
            summary = feature.path("properties").path("summary");
            geometry = feature.path("geometry").path("coordinates");
        } else {
            JsonNode route = root.path("routes").path(0);
            if (!"Ok".equals(root.path("code").asText())) throw new IllegalStateException("No road route found");
            summary = route;
            geometry = route.path("geometry").path("coordinates");
        }
        if (summary.isMissingNode() || !summary.has("distance") || !summary.has("duration")) {
            throw new IllegalStateException("No road route found");
        }
        double distanceMetres = summary.path("distance").asDouble(Double.NaN);
        double durationSeconds = summary.path("duration").asDouble(Double.NaN);
        if (!Double.isFinite(distanceMetres) || distanceMetres < 0
                || !Double.isFinite(durationSeconds) || durationSeconds < 0
                || durationSeconds > (Integer.MAX_VALUE - 1L) * 60) {
            throw new IllegalStateException("The routing service returned invalid route details");
        }
        double distanceKm = distanceMetres / 1000.0;
        int durationMinutes = (int) Math.ceil(durationSeconds / 60.0);
        List<List<Double>> coordinates = new ArrayList<>();
        for (JsonNode point : geometry) {
            double longitude = point.path(0).asDouble(Double.NaN);
            double latitude = point.path(1).asDouble(Double.NaN);
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                throw new IllegalStateException("The routing service returned invalid map coordinates");
            }
            coordinates.add(List.of(longitude, latitude));
        }
        if (coordinates.size() < 2) throw new IllegalStateException("No road route geometry found");
        return quoteFor(rate, distanceKm, durationMinutes, false, coordinates);
    }

    private FareQuote quoteFor(Rate rate, double distanceKm, int durationMinutes, boolean approximate,
                               List<List<Double>> coordinates) {
        int fare = roundFare(rate, distanceKm);
        return new FareQuote(fare, Math.round(distanceKm * 100.0) / 100.0, durationMinutes, approximate, coordinates);
    }

    public int fareForDistance(String vehicleType, double distanceKm) {
        if (!Double.isFinite(distanceKm) || distanceKm < 0) throw new IllegalArgumentException("Distance must be non-negative.");
        return roundFare(rateFor(vehicleType), distanceKm);
    }

    private int roundFare(Rate rate, double distanceKm) {
        double total = Math.max(rate.minimum(), rate.base() + distanceKm * rate.perKm());
        if (!Double.isFinite(total) || total > Integer.MAX_VALUE - 5) {
            throw new IllegalArgumentException("The calculated fare is outside the supported range.");
        }
        return (int) (Math.round(total / 5.0) * 5);
    }

    private Rate rateFor(String vehicleType) {
        String type = vehicleType == null ? "" : vehicleType.toUpperCase(Locale.ROOT);
        if (type.endsWith("BIKE")) return new Rate(bikeBase, bikeRate, bikeMinimum);
        if (type.endsWith("AUTO")) return new Rate(autoBase, autoRate, autoMinimum);
        if (type.endsWith("E-RICKSHAW")) return new Rate(erickshawBase, erickshawRate, erickshawMinimum);
        if (type.endsWith("MINI")) return new Rate(miniBase, miniRate, miniMinimum);
        if (type.endsWith("SUV")) return new Rate(suvBase, suvRate, suvMinimum);
        throw new IllegalArgumentException("Choose a supported vehicle type.");
    }

    private boolean validPair(Double latitude, Double longitude) {
        return latitude != null && longitude != null && Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    private record Rate(double base, double perKm, int minimum) {
        private Rate {
            if (!Double.isFinite(base) || base < 0 || !Double.isFinite(perKm) || perKm <= 0 || minimum <= 0) {
                throw new IllegalStateException("Fare configuration must use non-negative base fares, positive rates, and positive minimums.");
            }
        }
    }
    public record FareQuote(int fare, double distanceKm, int durationMinutes, boolean approximate,
                            List<List<Double>> routeCoordinates) { }
}
