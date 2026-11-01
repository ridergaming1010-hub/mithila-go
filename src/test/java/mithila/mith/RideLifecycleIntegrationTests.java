package mithila.mith;

import mithila.mith.model.UserAccount;
import mithila.mith.repository.RideBookingRepository;
import mithila.mith.service.RouteFareService;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.TestPropertySource;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "mithila.fares.mini.rate-per-km=23")
class RideLifecycleIntegrationTests extends ApiIntegrationTestSupport {
    @Autowired RouteFareService routeFareService;
    @Autowired RideBookingRepository rides;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;

    @Test
    void autoFareUsesConfiguredRateAndFiveRupeeRounding() {
        org.junit.jupiter.api.Assertions.assertEquals(160, routeFareService.fareForDistance("Mithila Auto", 8));
    }

    @Test
    void vehicleFareRatesIncreaseFromBikeToAutoToCarAndSuv() {
        int bike = routeFareService.fareForDistance("Mithila Bike", 8);
        int auto = routeFareService.fareForDistance("Mithila Auto", 8);
        int mini = routeFareService.fareForDistance("Mithila Mini", 8);
        int suv = routeFareService.fareForDistance("Mithila SUV", 8);

        org.junit.jupiter.api.Assertions.assertTrue(bike < auto);
        org.junit.jupiter.api.Assertions.assertTrue(auto < mini);
        org.junit.jupiter.api.Assertions.assertTrue(mini < suv);
    }

    @Test
    void driverMustBeNearPickupToStartEvenWithTheCorrectOtp() {
        UserAccount rider = createRider("Pickup Arrival Rider");
        UserAccount driver = createDriver("Pickup Arrival Driver");
        rideService.setOnline(driver.getId(), true, 26.36, 86.07);
        var ride = rideService.book(rider.getId(), "Madhubani Railway Station", "Town Hall", "Mithila Auto",
                null, 26.3543, 86.071, 26.367, 86.0731);
        ride = rideService.accept(driver.getId(), ride.getId());

        var assignedRide = ride;
        var error = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> rideService.changeStatus(driver.getId(), assignedRide.getId(),
                        "IN_PROGRESS", assignedRide.getOtp()));
        org.junit.jupiter.api.Assertions.assertEquals(
                "Move within 250 metres of the pickup and share your current location before starting the ride.",
                error.getReason());

        rideService.updateLocation(driver.getId(), 26.3543, 86.071);
        ride = rideService.changeStatus(driver.getId(), ride.getId(), "IN_PROGRESS", ride.getOtp());
        org.junit.jupiter.api.Assertions.assertEquals("IN_PROGRESS", ride.getStatus());
    }

    @Test
    void legacyRideRowsWithNullRouteAndReminderFieldsUseSafeDefaults() {
        UserAccount rider = createRider("Legacy Ride Rider");
        createDriver("Legacy Ride Driver");
        var ride = rideService.book(rider.getId(), "Railway Station", "Town Hall", "Mithila Auto",
                null, 26.3543, 86.0710, 26.3670, 86.0731);
        jdbcTemplate.update("""
                update ride_bookings
                set distance_km = null, duration_minutes = null, approximate_fare = null,
                    rider_reminder30_sent = null, rider_reminder10_sent = null,
                    driver_reminder30_sent = null, driver_reminder10_sent = null
                where id = ?
                """, ride.getId());
        entityManager.clear();

        var legacyRide = rides.findById(ride.getId()).orElseThrow();

        org.junit.jupiter.api.Assertions.assertEquals(0, legacyRide.getDistanceKm());
        org.junit.jupiter.api.Assertions.assertEquals(0, legacyRide.getDurationMinutes());
        org.junit.jupiter.api.Assertions.assertFalse(legacyRide.isApproximateFare());
        org.junit.jupiter.api.Assertions.assertFalse(legacyRide.isRiderReminder30Sent());
        org.junit.jupiter.api.Assertions.assertFalse(legacyRide.isRiderReminder10Sent());
        org.junit.jupiter.api.Assertions.assertFalse(legacyRide.isDriverReminder30Sent());
        org.junit.jupiter.api.Assertions.assertFalse(legacyRide.isDriverReminder10Sent());
    }

    @Test
    void instantRideWithoutADriverIsCancelledAfterTwoMinutes() {
        UserAccount rider = createRider("Ride Timeout Rider");
        UserAccount driver = createDriver("Ride Timeout Driver");
        rideService.setOnline(driver.getId(), true, 26.36, 86.07);
        var ride = rideService.book(rider.getId(), "Railway Station", "Town Hall", "Mithila Auto",
                null, 26.3543, 86.0710, 26.3670, 86.0731);
        org.junit.jupiter.api.Assertions.assertEquals("REQUESTED", ride.getStatus());

        org.springframework.test.util.ReflectionTestUtils.setField(ride, "createdAt",
                java.time.Instant.now().minus(java.time.Duration.ofMinutes(3)));
        rideService.processRideDeadlinesAndReminders();

        org.junit.jupiter.api.Assertions.assertEquals("CANCELLED", ride.getStatus());
    }

    @Test
    void tenMinuteScheduledRideIsOfferedAndRemindersReachRiderAndDriver() {
        UserAccount rider = createRider("Scheduled Reminder Rider");
        UserAccount driver = createDriver("Scheduled Reminder Driver");
        rideService.setOnline(driver.getId(), true, 26.36, 86.07);
        java.time.Instant pickup = java.time.Instant.now().plus(java.time.Duration.ofMinutes(10))
                .plusSeconds(30);

        var ride = rideService.book(rider.getId(), "Railway Station", "Town Hall", "Mithila Auto", pickup,
                26.3543, 86.0710, 26.3670, 86.0731);
        org.junit.jupiter.api.Assertions.assertTrue(rideService.driverOffers(driver.getId()).stream()
                .anyMatch(offer -> offer.getId().equals(ride.getId())));

        rideService.processRideDeadlinesAndReminders();

        var riderNotifications = rideService.unreadNotifications(rider.getId());
        var driverNotifications = rideService.unreadNotifications(driver.getId());
        org.junit.jupiter.api.Assertions.assertTrue(riderNotifications.stream()
                .anyMatch(item -> item.getMessage().contains("10 minutes")));
        org.junit.jupiter.api.Assertions.assertTrue(driverNotifications.stream()
                .anyMatch(item -> item.getMessage().contains("10 minutes")));
        org.junit.jupiter.api.Assertions.assertFalse(riderNotifications.stream()
                .anyMatch(item -> item.getMessage().contains("30 minutes")));
        org.junit.jupiter.api.Assertions.assertFalse(driverNotifications.stream()
                .anyMatch(item -> item.getMessage().contains("30 minutes")));
    }

    @Test
    void scheduledRideCanBeAcceptedInAdvanceButNotStartedBeforePickupTime() {
        UserAccount rider = createRider("Scheduled Start Rider");
        UserAccount driver = createDriver("Scheduled Start Driver");
        rideService.setOnline(driver.getId(), true, 26.3543, 86.0710);
        java.time.Instant pickup = java.time.Instant.now().plus(java.time.Duration.ofMinutes(20));
        var ride = rideService.book(rider.getId(), "Railway Station", "Town Hall", "Mithila Auto",
                pickup, 26.3543, 86.0710, 26.3670, 86.0731);

        org.junit.jupiter.api.Assertions.assertEquals("ASSIGNED", rideService.accept(driver.getId(), ride.getId()).getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(ride.getId(), rideService.driverOffers(driver.getId()).stream()
                .filter(item -> item.getStatus().equals("ASSIGNED"))
                .filter(item -> item.getId().equals(ride.getId()))
                .findFirst().orElseThrow().getId());
        rideService.updateLocation(driver.getId(), 26.3543, 86.0710);

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> rideService.changeStatus(driver.getId(), ride.getId(), "IN_PROGRESS", ride.getOtp()));
        org.junit.jupiter.api.Assertions.assertEquals("This scheduled ride cannot start before its pickup time.", error.getReason());
        org.junit.jupiter.api.Assertions.assertEquals("ASSIGNED", ride.getStatus());
    }

    @Test
    void completedRideMustBeAtTheDestinationAndCannotBeCompletedTwice() {
        UserAccount rider = createRider("Destination Check Rider");
        UserAccount driver = createDriver("Destination Check Driver");
        rideService.setOnline(driver.getId(), true, 26.3543, 86.0710);
        var ride = rideService.book(rider.getId(), "Railway Station", "Town Hall", "Mithila Auto",
                null, 26.3543, 86.0710, 26.3670, 86.0731);
        ride = rideService.accept(driver.getId(), ride.getId());
        long rideId = ride.getId();
        rideService.changeStatus(driver.getId(), rideId, "IN_PROGRESS", ride.getOtp());

        var tooFarError = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> rideService.changeStatus(driver.getId(), rideId, "COMPLETED", null));
        org.junit.jupiter.api.Assertions.assertEquals(org.springframework.http.HttpStatus.CONFLICT,
                tooFarError.getStatusCode());

        rideService.updateLocation(driver.getId(), 26.367, 86.0731);
        org.junit.jupiter.api.Assertions.assertEquals("COMPLETED",
                rideService.changeStatus(driver.getId(), rideId, "COMPLETED", null).getStatus());
        var duplicateError = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> rideService.changeStatus(driver.getId(), rideId, "COMPLETED", null));
        org.junit.jupiter.api.Assertions.assertEquals(org.springframework.http.HttpStatus.CONFLICT,
                duplicateError.getStatusCode());
    }

    @Test
    void riderTrackingHidesDriverCoordinatesWhenTheLastGpsUpdateIsStale() throws Exception {
        UserAccount rider = createRider("Stale Location Rider");
        UserAccount driver = createDriver("Stale Location Driver");
        rideService.setOnline(driver.getId(), true, 26.36, 86.07);
        var ride = rideService.book(rider.getId(), "Railway Station", "Town Hall", "Mithila Auto",
                null, 26.3543, 86.0710, 26.3670, 86.0731);
        rideService.accept(driver.getId(), ride.getId());
        MockHttpSession riderSession = login(rider);

        mockMvc.perform(get("/api/rides").session(riderSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].driverLatitude").value(26.36))
                .andExpect(jsonPath("$[0].driverLongitude").value(86.07));

        org.springframework.test.util.ReflectionTestUtils.setField(driver, "locationUpdatedAt",
                java.time.Instant.now().minus(java.time.Duration.ofMinutes(3)));
        entityManager.flush();

        mockMvc.perform(get("/api/rides").session(riderSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].driverLatitude").value(nullValue()))
                .andExpect(jsonPath("$[0].driverLongitude").value(nullValue()));
    }

    @Test
    void publicCatalogAndSessionBootstrapEndpointsRespond() throws Exception {
        mockMvc.perform(get("/api/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").isNotEmpty())
                .andExpect(jsonPath("$.token").isNotEmpty());
        mockMvc.perform(get("/api/landmarks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").isNotEmpty())
                .andExpect(jsonPath("$[0].latitude").isNumber())
                .andExpect(jsonPath("$[0].longitude").isNumber())
                .andExpect(jsonPath("$[*].name", hasItem("Saurath")))
                .andExpect(jsonPath("$[*].name", hasItem("Darbhanga Tower")))
                .andExpect(jsonPath("$[*].name", hasItem("Sitamarhi Railway Station")));
        mockMvc.perform(get("/api/estimate").param("vehicleType", "Mithila Bike"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fare").value(30));
        mockMvc.perform(get("/api/estimate").param("vehicleType", "Mithila Auto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fare").value(30));
        mockMvc.perform(get("/api/estimate").param("vehicleType", "Mithila Mini"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fare").value(50));
        mockMvc.perform(get("/api/estimate").param("vehicleType", "Prime Sedan"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/estimate").param("vehicleType", "Mithila SUV"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fare").value(80));
        mockMvc.perform(get("/api/estimate").param("vehicleType", "Mithila E-Rickshaw"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fare").value(30));
        mockMvc.perform(get("/api/rides")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/driver/rides")).andExpect(status().isUnauthorized());
    }

    @Test
    void bookingRequiresBothResolvedMapLocationsAndGeocodingRejectsInvalidCoordinates() throws Exception {
        UserAccount rider = createRider("Resolved Locations Rider");
        MockHttpSession riderSession = login(rider);

        mockMvc.perform(post("/api/book-ride").with(csrf()).session(riderSession)
                .contentType("application/json")
                .content("""
                        {"pickup":"Some address","destination":"Another address","vehicleType":"Mithila Auto"}
                        """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").isNotEmpty());

        mockMvc.perform(get("/api/geocode/reverse").session(riderSession)
                .param("latitude", "91").param("longitude", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void riderBooksRideDriverCompletesItAndRiderReportsIssue() throws Exception {
        UserAccount rider = createRider("Lifecycle Rider");
        UserAccount driver = createDriver("Lifecycle Driver");
        MockHttpSession riderSession = login(rider);
        MockHttpSession driverSession = login(driver);
        mockMvc.perform(put("/api/driver/online").with(csrf()).session(driverSession)
                        .contentType("application/json").content("{\"online\":true,\"latitude\":26.36,\"longitude\":86.07}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online").value(true));

        MvcResult booking = mockMvc.perform(post("/api/book-ride").with(csrf()).session(riderSession)
                        .contentType("application/json")
                        .content("""
                                {"pickup":"Madhubani Station","destination":"Town Hall","vehicleType":"Mithila Auto","pickupLatitude":26.3543,"pickupLongitude":86.071,"dropoffLatitude":26.367,"dropoffLongitude":86.0731}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.driverAvailable").value(true))
                .andExpect(jsonPath("$.pickupLatitude").value(26.3543))
                .andExpect(jsonPath("$.dropoffLongitude").value(86.0731))
                .andExpect(jsonPath("$.fare").isNumber())
                .andReturn();
        long rideId = number(booking, "$.id");
        mockMvc.perform(get("/api/driver/rides").session(driverSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem((int) rideId)))
                .andExpect(jsonPath("$[0].pickupLongitude").value(86.071));
        mockMvc.perform(post("/api/driver/rides/{rideId}/accept", rideId).with(csrf()).session(driverSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"));
        MvcResult riderHistory = mockMvc.perform(get("/api/rides").session(riderSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].driverAvailable").value(true))
                .andExpect(jsonPath("$[0].dropoffLatitude").value(26.367))
                .andReturn();
        String otp = text(riderHistory, "$[0].otp");
        mockMvc.perform(patch("/api/rides/{rideId}/status", rideId).with(csrf()).session(driverSession)
                        .param("status", "IN_PROGRESS").param("otp", otp))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/driver/location").with(csrf()).session(driverSession)
                        .contentType("application/json")
                        .content("{\"latitude\":26.3543,\"longitude\":86.071}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/rides/{rideId}/status", rideId).with(csrf()).session(driverSession)
                        .param("status", "IN_PROGRESS").param("otp", otp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mockMvc.perform(patch("/api/rides/{rideId}/status", rideId).with(csrf()).session(driverSession)
                        .param("status", "COMPLETED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("within 250 metres")));
        mockMvc.perform(put("/api/driver/location").with(csrf()).session(driverSession)
                        .contentType("application/json")
                        .content("{\"latitude\":26.367,\"longitude\":86.0731}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/rides/{rideId}/status", rideId).with(csrf()).session(driverSession)
                        .param("status", "COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mockMvc.perform(patch("/api/rides/{rideId}/status", rideId).with(csrf()).session(driverSession)
                        .param("status", "COMPLETED"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/complaints").with(csrf()).session(riderSession)
                        .contentType("application/json")
                        .content("""
                                {"rideId":%d,"category":"Fare or payment dispute","details":"Please review the fare."}
                                """.formatted(rideId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rideId").value(rideId))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(get("/api/complaints").session(riderSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    private static long number(MvcResult result, String expression) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), expression);
        return value.longValue();
    }

    private static String text(MvcResult result, String expression) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), expression);
    }
}
