package mithila.mith;

import mithila.mith.model.UserAccount;
import mithila.mith.repository.RideOfferRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DriverPortalIntegrationTests extends ApiIntegrationTestSupport {
        @Autowired
        private RideOfferRepository offers;

        @Test
        void driverCannotGoOnlineWithoutLocation() throws Exception {
                UserAccount driver = createDriver("Location Required Driver");
                MockHttpSession driverSession = login(driver);

                mockMvc.perform(put("/api/driver/online").with(csrf()).session(driverSession)
                                .contentType("application/json").content("{\"online\":true}"))
                                .andExpect(status().isBadRequest());
                assertEquals(false, rideService.getUser(driver.getId()).isOnline());
        }

        @Test
        void noOnlineDriverIsReportedAndTheRideCanBeOfferedWhenOneComesOnline() {
                UserAccount rider = createRider("Offline Matching Rider");
                UserAccount driver = createDriver("Offline Matching Driver");

                var ride = rideService.book(rider.getId(), "Railway Station", "Town Hall", "Mithila Auto",
                        null, 26.3543, 86.0710, 26.3670, 86.0731);
                assertEquals("NO_DRIVER_AVAILABLE", ride.getStatus());

                rideService.setOnline(driver.getId(), true, 26.36, 86.07);
                assertEquals("REQUESTED", ride.getStatus());
                assertEquals("PENDING", offers.findByRideIdAndDriverId(ride.getId(), driver.getId())
                        .orElseThrow().getStatus());
        }

        @Test
        void anOfflineMatchingDriverDoesNotBlockNoDriverStatusAfterAllOnlineDriversReject() {
                UserAccount rider = createRider("Mixed Availability Rider");
                UserAccount onlineDriver = createDriver("Online Matching Driver");
                UserAccount offlineDriver = createDriver("Offline Matching Driver");
                rideService.setOnline(onlineDriver.getId(), true, 26.36, 86.07);
                var ride = rideService.book(rider.getId(), "Railway Station", "Town Hall", "Mithila Auto",
                        null, 26.3543, 86.0710, 26.3670, 86.0731);

                var rejectedRide = rideService.reject(onlineDriver.getId(), ride.getId());
                assertEquals("NO_DRIVER_AVAILABLE", rejectedRide.getStatus());

                rideService.setOnline(offlineDriver.getId(), true, 26.35, 86.07);
                assertEquals("REQUESTED", ride.getStatus());
                assertEquals("PENDING", offers.findByRideIdAndDriverId(ride.getId(), offlineDriver.getId())
                        .orElseThrow().getStatus());
        }

        @Test
        void offlinePendingOffersDoNotLeaveRideSearching() {
                UserAccount rider = createRider("Offline Rejection Rider");
                UserAccount rejectingDriver = createDriver("Rejecting Driver");
                UserAccount waitingDriver = createDriver("Offline Waiting Driver");
                rideService.setOnline(rejectingDriver.getId(), true, 26.36, 86.07);
                rideService.setOnline(waitingDriver.getId(), true, 26.35, 86.07);
                var ride = rideService.book(rider.getId(), "Railway Station", "Town Hall", "Mithila Auto",
                        null, 26.3543, 86.0710, 26.3670, 86.0731);

                assertEquals("REQUESTED", rideService.reject(rejectingDriver.getId(), ride.getId()).getStatus());
                rideService.setOnline(waitingDriver.getId(), false);

                assertEquals("NO_DRIVER_AVAILABLE", ride.getStatus());
        }

        @Test
        void anotherOnlineDriverCanAcceptAfterTheFirstDriverRejects() {
                UserAccount rider = createRider("Second Driver Rider");
                UserAccount firstDriver = createDriver("First Matching Driver");
                UserAccount secondDriver = createDriver("Second Matching Driver");
                rideService.setOnline(firstDriver.getId(), true, 26.36, 86.07);
                rideService.setOnline(secondDriver.getId(), true, 26.35, 86.07);
                var ride = rideService.book(rider.getId(), "Railway Station", "Town Hall", "Mithila Auto",
                        null, 26.3543, 86.0710, 26.3670, 86.0731);

                assertEquals("REQUESTED", rideService.reject(firstDriver.getId(), ride.getId()).getStatus());
                assertEquals("ASSIGNED", rideService.accept(secondDriver.getId(), ride.getId()).getStatus());
                assertEquals(secondDriver.getId(), ride.getDriver().getId());
        }

        @Test
        void onlyTheFirstDriverCanAcceptARide() {
                UserAccount rider = createRider("Single Assignment Rider");
                UserAccount firstDriver = createDriver("First Assignment Driver");
                UserAccount secondDriver = createDriver("Second Assignment Driver");
                rideService.setOnline(firstDriver.getId(), true, 26.36, 86.07);
                rideService.setOnline(secondDriver.getId(), true, 26.35, 86.07);
                var ride = rideService.book(rider.getId(), "Railway Station", "Town Hall", "Mithila Auto",
                        null, 26.3543, 86.0710, 26.3670, 86.0731);

                rideService.accept(firstDriver.getId(), ride.getId());

                var error = org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> rideService.accept(secondDriver.getId(), ride.getId()));
                assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.getStatusCode());
                assertEquals(firstDriver.getId(), ride.getDriver().getId());
        }

        @Test
        void dispatchUsesTheConfiguredTwentyKilometreRadiusAndRecoversWhenDriverMovesNear() {
                UserAccount rider = createRider("Dispatch Radius Rider");
                UserAccount driver = createDriver("Dispatch Radius Driver");
                rideService.setOnline(driver.getId(), true, 26.90, 86.07);

                var ride = rideService.book(rider.getId(), "Railway Station", "Town Hall", "Mithila Auto",
                        null, 26.3543, 86.0710, 26.3670, 86.0731);
                assertEquals("NO_DRIVER_AVAILABLE", ride.getStatus());

                rideService.updateLocation(driver.getId(), 26.36, 86.07);

                assertEquals("REQUESTED", ride.getStatus());
                assertEquals("PENDING", offers.findByRideIdAndDriverId(ride.getId(), driver.getId())
                        .orElseThrow().getStatus());
        }

        @Test
        void newVehicleSignupMakesTheVehicleAvailableWithoutRestart() throws Exception {
                UserAccount rider = createRider("Availability Rider");
                MockHttpSession riderSession = login(rider);
                String suffix = java.util.UUID.randomUUID().toString().replace("-", "");

                mockMvc.perform(get("/api/vehicles").session(riderSession))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[2].registeredDrivers").value(0));

                rideService.signup("New E-Rickshaw Driver", "new-e-rickshaw-" + suffix + "@example.com",
                        randomPhone("8"), TEST_PASSWORD, "DRIVER", "Mithila E-Rickshaw", "BR-01-" + suffix.substring(0, 6));

                mockMvc.perform(get("/api/vehicles").session(riderSession))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[2].registeredDrivers").value(1));
        }

        @Test
        void unsupportedVehicleTypeCannotBePersistedInDriverProfile() throws Exception {
                UserAccount driver = createDriver("Vehicle Validation Driver");
                MockHttpSession driverSession = login(driver);

                mockMvc.perform(put("/api/profile").with(csrf()).session(driverSession)
                                                .contentType("application/json")
                                                .content("""
                                                                {"name":"Vehicle Validation Driver","phone":"%s","vehicleType":"Other","vehiclePlate":"BR-01-VALID"}
                                                                """.formatted(driver.getPhone())))
                                .andExpect(status().isBadRequest());

                assertEquals("Auto", rideService.getUser(driver.getId()).getVehicleType());
        }

    @Test
    void suvDriverReceivesMatchingRideOffers() throws Exception {
        UserAccount rider = createRider("SUV Ride Rider");
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "");
        UserAccount driver = rideService.signup("SUV Driver", "suv-" + suffix + "@example.com",
                randomPhone("7"), TEST_PASSWORD, "DRIVER", "Mithila SUV", "BR-01-" + suffix.substring(0, 6));
        MockHttpSession driverSession = login(driver);

        mockMvc.perform(put("/api/driver/online").with(csrf()).session(driverSession)
                        .contentType("application/json").content("{\"online\":true,\"latitude\":26.36,\"longitude\":86.07}"))
                .andExpect(status().isOk());
        rideService.book(rider.getId(), "Railway Station", "Town Hall", "Mithila SUV",
                null, 26.3543, 86.0710, 26.3670, 86.0731);
        long rideId = rideService.riderHistory(rider.getId()).get(0).getId();

        mockMvc.perform(get("/api/driver/rides").session(driverSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem((int) rideId)));
    }

    @Test
    void eRickshawDriverReceivesMatchingRideOffers() throws Exception {
        UserAccount rider = createRider("E-Rickshaw Ride Rider");
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "");
        UserAccount driver = rideService.signup("E-Rickshaw Driver", "e-rickshaw-" + suffix + "@example.com",
                randomPhone("7"), TEST_PASSWORD, "DRIVER", "Mithila E-Rickshaw",
                "BR-01-" + suffix.substring(0, 6));
        MockHttpSession driverSession = login(driver);

        mockMvc.perform(put("/api/driver/online").with(csrf()).session(driverSession)
                        .contentType("application/json").content("{\"online\":true,\"latitude\":26.36,\"longitude\":86.07}"))
                .andExpect(status().isOk());
        rideService.book(rider.getId(), "Railway Station", "Town Hall", "Mithila E-Rickshaw",
                null, 26.3543, 86.0710, 26.3670, 86.0731);
        long rideId = rideService.riderHistory(rider.getId()).get(0).getId();

        mockMvc.perform(get("/api/driver/rides").session(driverSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem((int) rideId)));
    }

    @Test
    void driverCanGoOnlineShareLocationRejectOfferAndGoOffline() throws Exception {
        UserAccount rider = createRider("Offer Rider");
        UserAccount driver = createDriver("Offer Driver");
        MockHttpSession riderSession = login(rider);
        MockHttpSession driverSession = login(driver);
        rideService.book(rider.getId(), "Railway Station", "Town Hall", "Mithila Auto",
                null, 26.3543, 86.0710, 26.3670, 86.0731);
        long rideId = rideService.riderHistory(rider.getId()).get(0).getId();

        mockMvc.perform(get("/api/driver/rides").session(driverSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(put("/api/driver/online").with(csrf()).session(driverSession)
                        .contentType("application/json").content("{\"online\":true,\"latitude\":26.36,\"longitude\":86.07}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/driver/location").with(csrf()).session(driverSession)
                        .contentType("application/json")
                        .content("{\"latitude\":26.35,\"longitude\":86.07}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastLatitude").value(26.35))
                .andExpect(jsonPath("$.lastLongitude").value(86.07));

        mockMvc.perform(get("/api/driver/rides").session(driverSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem((int) rideId)));
        mockMvc.perform(post("/api/driver/rides/{rideId}/reject", rideId).with(csrf()).session(driverSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_DRIVER_AVAILABLE"));
        mockMvc.perform(get("/api/rides").session(riderSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("NO_DRIVER_AVAILABLE"));
        org.junit.jupiter.api.Assertions.assertEquals("REJECTED",
                offers.findByRideIdAndDriverId(rideId, driver.getId()).orElseThrow().getStatus());
        mockMvc.perform(get("/api/driver/rides").session(driverSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem((int) rideId))));
        mockMvc.perform(post("/api/driver/rides/{rideId}/reject", rideId).with(csrf()).session(driverSession))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/api/driver/online").with(csrf()).session(driverSession)
                        .contentType("application/json").content("{\"online\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online").value(false))
                .andExpect(jsonPath("$.lastLatitude").value(nullValue()));
        mockMvc.perform(get("/api/driver/rides"))
                .andExpect(status().isUnauthorized());
    }
}
