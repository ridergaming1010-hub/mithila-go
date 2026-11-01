package mithila.mith.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import mithila.mith.model.RideBooking;
import mithila.mith.model.RideComplaint;
import mithila.mith.model.RideOffer;
import mithila.mith.model.RideNotification;
import mithila.mith.model.UserAccount;
import mithila.mith.repository.RideComplaintRepository;
import mithila.mith.repository.RideOfferRepository;
import mithila.mith.repository.RideBookingRepository;
import mithila.mith.repository.RideNotificationRepository;
import mithila.mith.repository.UserAccountRepository;
import mithila.mith.security.AadhaarHmacKey;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;

@Service
@Transactional
public class RideService {
    private static final double MAX_PICKUP_DISTANCE_METRES = 250;
    private static final Duration MAX_DRIVER_LOCATION_AGE = Duration.ofMinutes(2);
    private static final Duration IMMEDIATE_RIDE_TIMEOUT = Duration.ofMinutes(2);
    @Value("${mithila.dispatch.radius-km:20}")
    private double dispatchRadiusKm;
    private final UserAccountRepository users;
    private final RideBookingRepository rides;
    private final RideOfferRepository offers;
    private final RideComplaintRepository complaints;
    private final RideNotificationRepository notifications;
    private final RouteFareService fareService;
    private final String aadhaarHmacKey;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public RideService(UserAccountRepository users, RideBookingRepository rides, RideOfferRepository offers,
                       RideComplaintRepository complaints,
                       RideNotificationRepository notifications,
                       RouteFareService fareService,
                       AadhaarHmacKey aadhaarHmacKey) {
        this.users = users;
        this.rides = rides;
        this.offers = offers;
        this.complaints = complaints;
        this.notifications = notifications;
        this.fareService = fareService;
        this.aadhaarHmacKey = aadhaarHmacKey.value();
    }

    public UserAccount signup(String name, String email, String phone, String password, String role,
                              String vehicleType, String vehiclePlate) {
        return signup(name, email, phone, password, password, role, vehicleType, vehiclePlate);
    }

    public UserAccount signup(String name, String email, String phone, String password, String confirmPassword,
                              String role, String vehicleType, String vehiclePlate) {
        return signup(name, email, phone, password, confirmPassword, role, vehicleType, vehiclePlate, null, null);
    }

    public UserAccount signup(String name, String email, String phone, String password, String confirmPassword,
                      String role, String vehicleType, String vehiclePlate, String aadhaarLastFour,
                      String aadhaarNumber) {
        String normalizedRole = normalizeRole(role);
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String normalizedPhone = normalizePhone(phone);
        if (!normalizedPhone.matches("[0-9]{10}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid 10-digit phone number.");
        }
        if (confirmPassword != null && !confirmPassword.isBlank() && !password.equals(confirmPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passwords do not match.");
        }
        if (!"DRIVER".equals(normalizedRole) && aadhaarNumber != null && !aadhaarNumber.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aadhaar sign-in is only available for driver accounts.");
        }
        if (users.existsByEmailIgnoreCase(normalizedEmail) || users.existsByPhone(normalizedPhone)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account already exists with that email or phone.");
        }
        String normalizedVehicleType = vehicleType == null ? null : vehicleType.trim();
        if ("DRIVER".equals(normalizedRole) && (normalizedVehicleType == null
                || !List.of("AUTO", "BIKE", "MITHILA E-RICKSHAW", "MITHILA MINI", "MITHILA SUV")
                        .contains(normalizedVehicleType.toUpperCase(Locale.ROOT))
                || vehiclePlate == null || vehiclePlate.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose a supported vehicle and enter the vehicle plate.");
        }
        if (aadhaarLastFour != null && !aadhaarLastFour.isBlank() && !aadhaarLastFour.matches("[0-9]{4}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter only the last four Aadhaar digits.");
        }
        String aadhaarFingerprint = null;
        String savedAadhaarLastFour = aadhaarLastFour;
        if (aadhaarNumber != null && !aadhaarNumber.isBlank()) {
            String normalizedAadhaar = normalizeAadhaar(aadhaarNumber);
            aadhaarFingerprint = fingerprintAadhaar(normalizedAadhaar);
            if (users.findByAadhaarFingerprint(aadhaarFingerprint).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "That Aadhaar number is already linked to a driver account.");
            }
            savedAadhaarLastFour = normalizedAadhaar.substring(8);
        }
        UserAccount account = new UserAccount(name.trim(), normalizedEmail, normalizedPhone,
                passwordEncoder.encode(password), normalizedRole,
                "DRIVER".equals(normalizedRole) ? vehiclePlate.trim().toUpperCase(Locale.ROOT) : null,
                normalizedVehicleType,
                "DRIVER".equals(normalizedRole) && savedAadhaarLastFour != null && !savedAadhaarLastFour.isBlank()
                    ? savedAadhaarLastFour : null);
        if ("DRIVER".equals(normalizedRole)) account.setAadhaarFingerprint(aadhaarFingerprint);
        return users.save(account);
    }

    public UserAccount login(String identifier, String password, String role) {
        String normalizedRole = normalizeRole(role);
        String lookup = identifier.trim();
        var accountLookup = "DRIVER".equals(normalizedRole) && lookup.matches("[0-9]{12}")
                ? users.findByAadhaarFingerprint(fingerprintAadhaar(lookup))
                : users.findByEmailIgnoreCaseOrPhone(lookup.toLowerCase(Locale.ROOT), normalizePhone(lookup));
        UserAccount user = accountLookup
                .filter(account -> account.getRole().equals(normalizedRole)
                        && passwordEncoder.matches(password, account.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Email/phone or password is incorrect."));
        return user;
    }

    public UserAccount getUser(long id) {
        return users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again."));
    }

    public int estimateFare(String vehicleType) {
        return fareService.quote(vehicleType, null, null, null, null).fare();
    }

    public RouteFareService.FareQuote quoteFare(String vehicleType, Double pickupLatitude, Double pickupLongitude,
                                                Double dropoffLatitude, Double dropoffLongitude) {
        return fareService.quote(vehicleType, pickupLatitude, pickupLongitude, dropoffLatitude, dropoffLongitude);
    }

    public List<VehicleAvailability> vehicleAvailability() {
        return List.of(
                vehicle("Mithila Bike", "Bike"),
                vehicle("Mithila Auto", "Auto"),
                vehicle("Mithila E-Rickshaw", "Mithila E-Rickshaw"),
                vehicle("Mithila Mini", "Mithila Mini"),
                vehicle("Mithila SUV", "Mithila SUV"));
    }

    private VehicleAvailability vehicle(String displayName, String driverType) {
        return new VehicleAvailability(displayName,
                users.countByRoleAndVehicleTypeIgnoreCase("DRIVER", driverType),
                users.findByRoleAndOnlineTrueAndVehicleTypeIgnoreCase("DRIVER", driverType).size());
    }

    public RideBooking book(long riderId, String pickup, String destination, String vehicleType) {
        return book(riderId, pickup, destination, vehicleType, null, null, null);
    }

    public RideBooking book(long riderId, String pickup, String destination, String vehicleType, Instant scheduledFor,
                            Double pickupLatitude, Double pickupLongitude) {
        return book(riderId, pickup, destination, vehicleType, scheduledFor, pickupLatitude, pickupLongitude,
                null, null);
    }

    public RideBooking book(long riderId, String pickup, String destination, String vehicleType, Instant scheduledFor,
                            Double pickupLatitude, Double pickupLongitude, Double dropoffLatitude,
                            Double dropoffLongitude) {
        UserAccount rider = requireRole(riderId, "RIDER");
        if (!validCoordinatesPair(pickupLatitude, pickupLongitude)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid pickup location on the map.");
        }
        if (!validCoordinatesPair(dropoffLatitude, dropoffLongitude)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid destination on the map.");
        }
        if (scheduledFor != null && !scheduledFor.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a departure time in the future.");
        }
        RouteFareService.FareQuote fareQuote;
        try {
            fareQuote = quoteFare(vehicleType, pickupLatitude, pickupLongitude, dropoffLatitude, dropoffLongitude);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
        int fare = fareQuote.fare();
        VehicleAvailability availability = vehicleAvailability().stream()
                .filter(item -> item.vehicleType().equals(vehicleType)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a supported vehicle."));
        if (availability.registeredDrivers() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This vehicle is coming soon. Choose another vehicle.");
        }
        String otp = String.format("%04d", secureRandom.nextInt(10_000));
        RideBooking ride = new RideBooking(rider, pickup.trim(), destination.trim(), vehicleType, fare, otp,
                scheduledFor, pickupLatitude, pickupLongitude);
        if (dropoffLatitude != null && dropoffLongitude != null) {
            ride.setDropoffLocation(dropoffLatitude.doubleValue(), dropoffLongitude.doubleValue());
        }
        ride.applyFareQuote(fareQuote.distanceKm(), fareQuote.durationMinutes(), fareQuote.approximate());
        rides.save(ride);
        dispatch(ride);
        if (!hasAvailableDriver(ride)) noDriverAvailable(ride);
        return ride;
    }

    @Transactional(readOnly = true)
    public List<RideBooking> riderHistory(long riderId) {
        requireRole(riderId, "RIDER");
        return rides.findByRiderIdOrderByCreatedAtDesc(riderId);
    }

    public boolean hasAvailableDriver(RideBooking ride) {
        if (!"REQUESTED".equals(ride.getStatus())) return ride.getDriver() != null;
        String requestedVehicle = vehicleClass(ride.getVehicleType());
        return requestedVehicle != null && ride.getOffers().stream()
                .anyMatch(offer -> "PENDING".equals(offer.getStatus())
                        && requestedVehicle.equals(vehicleClass(offer.getDriver().getVehicleType()))
                        && isEligibleDriver(offer.getDriver(), ride));
    }

    public List<RideBooking> driverOffers(long driverId) {
        UserAccount driver = requireRole(driverId, "DRIVER");
        if (driver.isOnline()) {
            rides.findDispatchableRides(Instant.now()).forEach(this::dispatch);
        }
        List<RideBooking> dashboard = new ArrayList<>();
        if (driver.isOnline()) {
            dashboard.addAll(offers.findByDriverIdAndStatusOrderByRideCreatedAtDesc(driver.getId(), "PENDING")
                    .stream().map(offer -> offer.getRide())
                    .filter(ride -> "REQUESTED".equals(ride.getStatus()) && isEligibleDriver(driver, ride))
                    .toList());
        }
        rides.findByDriverIdOrderByCreatedAtDesc(driver.getId()).stream()
            .filter(ride -> "ASSIGNED".equals(ride.getStatus()) || "IN_PROGRESS".equals(ride.getStatus()))
            .forEach(dashboard::add);
        return dashboard;
    }

    public RideBooking accept(long driverId, long rideId) {
        UserAccount driver = requireRole(driverId, "DRIVER");
        if (!driver.isOnline()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Go online before accepting a ride.");
        }
        RideBooking ride = rides.findByIdForUpdate(rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found."));
        if (!"REQUESTED".equals(ride.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This ride is no longer available.");
        }
        if (!isEligibleDriver(driver, ride)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This ride is outside your eligible dispatch area. Refresh your location and try again.");
        }
        RideOffer offer = offers.findByRideIdAndDriverId(rideId, driverId)
                .filter(existing -> "PENDING".equals(existing.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride offer not found."));
        offer.accept();
        ride.assignTo(driver);
        return ride;
    }

    public RideBooking reject(long driverId, long rideId) {
        UserAccount driver = requireRole(driverId, "DRIVER");
        if (!driver.isOnline()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Go online before responding to a ride offer.");
        }
        RideBooking ride = rides.findByIdForUpdate(rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found."));
        if (!"REQUESTED".equals(ride.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This ride is no longer available.");
        }
        RideOffer offer = offers.findByRideIdAndDriverId(rideId, driver.getId())
                .filter(existing -> "PENDING".equals(existing.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride offer not found."));
        offer.reject();
        dispatch(ride);
        if (allMatchingDriversRejected(ride)) {
            ride.setStatus("NO_DRIVER_AVAILABLE");
            notifyUser(ride.getRider(), ride,
                    "No driver accepted this ride. Try again or choose another vehicle.");
        }
        return ride;
    }

    public RideBooking changeStatus(long userId, long rideId, String nextStatus, String providedOtp) {
        RideBooking ride = rides.findByIdForUpdate(rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found."));
        boolean isRider = ride.getRider().getId().equals(userId);
        boolean isAssignedDriver = ride.getDriver() != null && ride.getDriver().getId().equals(userId);
        if (!isRider && !isAssignedDriver) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot update this ride.");
        }
        boolean allowed = switch (nextStatus) {
            case "IN_PROGRESS" -> isAssignedDriver && "ASSIGNED".equals(ride.getStatus());
            case "COMPLETED" -> isAssignedDriver && "IN_PROGRESS".equals(ride.getStatus());
            case "CANCELLED" -> isRider && ("REQUESTED".equals(ride.getStatus()) || "ASSIGNED".equals(ride.getStatus()));
            default -> false;
        };
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That ride status change is not allowed.");
        }
        if ("IN_PROGRESS".equals(nextStatus) && (providedOtp == null || !MessageDigest.isEqual(
                ride.getOtp().getBytes(StandardCharsets.UTF_8), providedOtp.getBytes(StandardCharsets.UTF_8)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The rider OTP does not match.");
        }
        if ("IN_PROGRESS".equals(nextStatus)) {
            if (ride.getScheduledFor() != null && ride.getScheduledFor().isAfter(Instant.now())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This scheduled ride cannot start before its pickup time.");
            }
            ensureDriverAtPickup(ride);
        } else if ("COMPLETED".equals(nextStatus)) {
            ensureDriverAtDestination(ride);
        }
        ride.setStatus(nextStatus);
        return ride;
    }

    private void ensureDriverAtPickup(RideBooking ride) {
        UserAccount driver = ride.getDriver();
        requireFreshDriverLocation(driver, "Share your current location and go online before starting the ride.");
        if (ride.getPickupLatitude() == null || ride.getPickupLongitude() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This pickup has no map location. Ask the rider to select a landmark or place a pickup pin.");
        }

        double distanceMetres = haversineDistanceMetres(driver.getLastLatitude(), driver.getLastLongitude(),
                ride.getPickupLatitude(), ride.getPickupLongitude());
        if (distanceMetres > MAX_PICKUP_DISTANCE_METRES) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Move within 250 metres of the pickup and share your current location before starting the ride.");
        }
    }

    private void ensureDriverAtDestination(RideBooking ride) {
        UserAccount driver = ride.getDriver();
        requireFreshDriverLocation(driver, "Share your current location and stay online before completing the ride.");
        if (ride.getDropoffLatitude() == null || ride.getDropoffLongitude() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This ride has no mapped destination. Contact support before completing it.");
        }
        double distanceMetres = haversineDistanceMetres(driver.getLastLatitude(), driver.getLastLongitude(),
                ride.getDropoffLatitude(), ride.getDropoffLongitude());
        if (distanceMetres > MAX_PICKUP_DISTANCE_METRES) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Move within 250 metres of the destination and share your current location before completing the ride.");
        }
    }

    private void requireFreshDriverLocation(UserAccount driver, String message) {
        if (!hasFreshDriverLocation(driver)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, message);
        }
    }

    public boolean hasFreshDriverLocation(UserAccount driver) {
        if (driver == null || !driver.isOnline() || driver.getLastLatitude() == null || driver.getLastLongitude() == null
                || !validCoordinates(driver.getLastLatitude(), driver.getLastLongitude())) return false;
        Instant locationUpdatedAt = driver.getLocationUpdatedAt();
        return locationUpdatedAt != null && !locationUpdatedAt.isBefore(Instant.now().minus(MAX_DRIVER_LOCATION_AGE));
    }

    private double haversineDistanceMetres(double latitude1, double longitude1, double latitude2, double longitude2) {
        double latitudeDistance = Math.toRadians(latitude2 - latitude1);
        double longitudeDistance = Math.toRadians(longitude2 - longitude1);
        double a = Math.sin(latitudeDistance / 2) * Math.sin(latitudeDistance / 2)
                + Math.cos(Math.toRadians(latitude1)) * Math.cos(Math.toRadians(latitude2))
                * Math.sin(longitudeDistance / 2) * Math.sin(longitudeDistance / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public UserAccount setOnline(long userId, boolean online) {
        return setOnline(userId, online, null, null);
    }

    public UserAccount setOnline(long userId, boolean online, Double latitude, Double longitude) {
        UserAccount driver = requireRole(userId, "DRIVER");
        if (online) {
            if (latitude == null || longitude == null || !validCoordinates(latitude, longitude)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Share your current location before going online.");
            }
            driver.setLocation(latitude, longitude);
            driver.setOnline(true);
        } else {
            driver.setOnline(false);
        }
        if (online) {
            List<RideBooking> waiting = new ArrayList<>(rides.findByStatusOrderByCreatedAtAsc("REQUESTED"));
            waiting.addAll(rides.findByStatusOrderByCreatedAtAsc("NO_DRIVER_AVAILABLE"));
            waiting.forEach(ride -> {
                if (ride.getScheduledFor() != null && ride.getScheduledFor().isBefore(Instant.now())) return;
                if (ride.getScheduledFor() == null && ride.getCreatedAt().plus(Duration.ofMinutes(5)).isBefore(Instant.now())) return;
                if ("NO_DRIVER_AVAILABLE".equals(ride.getStatus())) ride.setStatus("REQUESTED");
                dispatch(ride);
                if (allMatchingDriversRejected(ride)) noDriverAvailable(ride);
            });
        } else {
            rides.findByStatusOrderByCreatedAtAsc("REQUESTED").stream()
                    .filter(ride -> vehicleClass(driver.getVehicleType()) != null
                            && vehicleClass(driver.getVehicleType()).equals(vehicleClass(ride.getVehicleType())))
                    .filter(ride -> !hasAvailableDriver(ride))
                    .forEach(this::noDriverAvailable);
        }
        return driver;
    }

    public UserAccount updateProfile(long userId, String name, String phone, String profileImageUrl,
                                     String currentPassword, String newPassword, String confirmPassword) {
        return updateProfile(userId, name, phone, profileImageUrl, currentPassword, newPassword,
                confirmPassword, null, null, null);
    }

    public UserAccount updateProfile(long userId, String name, String phone, String profileImageUrl,
                                     String currentPassword, String newPassword, String confirmPassword,
                                     String vehicleType, String vehiclePlate, String aadhaarNumber) {
        UserAccount user = getUser(userId);
        String normalizedPhone = normalizePhone(phone);
        if (!normalizedPhone.matches("[0-9]{10}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid 10-digit phone number.");
        }
        if (users.existsByPhoneAndIdNot(normalizedPhone, userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That phone number is already in use.");
        }
        if (profileImageUrl != null && !profileImageUrl.isBlank()
                && !profileImageUrl.matches("(?i)^https://[^\\s]{1,490}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Profile pictures must use a secure HTTPS URL.");
        }
        if (!"DRIVER".equals(user.getRole()) && aadhaarNumber != null && !aadhaarNumber.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aadhaar sign-in is only available for driver accounts.");
        }
        String updatedProfileImageUrl = profileImageUrl == null ? user.getProfileImageUrl()
            : profileImageUrl.isBlank() ? null : profileImageUrl.trim();
        user.updateProfile(name.trim(), normalizedPhone, updatedProfileImageUrl);
        if ("DRIVER".equals(user.getRole())) {
            String updatedVehicleType = vehicleType == null ? user.getVehicleType() : vehicleType.trim();
            String updatedVehiclePlate = vehiclePlate == null ? user.getVehiclePlate()
                    : vehiclePlate.trim().toUpperCase(Locale.ROOT);
            boolean updatingVehicle = vehicleType != null || vehiclePlate != null;
            if (updatingVehicle && (updatedVehicleType == null || !List.of("AUTO", "BIKE", "MITHILA E-RICKSHAW",
                "MITHILA MINI", "MITHILA SUV")
                    .contains(updatedVehicleType.toUpperCase(Locale.ROOT))
                    || updatedVehiclePlate == null || updatedVehiclePlate.isBlank()
                || updatedVehiclePlate.length() > 20)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Choose a supported vehicle and enter a valid vehicle plate.");
            }
            String aadhaarFingerprint = null;
            String aadhaarLastFour = null;
            if (aadhaarNumber != null && !aadhaarNumber.isBlank()) {
                String normalizedAadhaar = normalizeAadhaar(aadhaarNumber);
                aadhaarFingerprint = fingerprintAadhaar(normalizedAadhaar);
                if (users.existsByAadhaarFingerprintAndIdNot(aadhaarFingerprint, userId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "That Aadhaar number is already linked to another driver account.");
                }
                aadhaarLastFour = normalizedAadhaar.substring(8);
            }
            user.updateDriverProfile(updatedVehicleType, updatedVehiclePlate, aadhaarFingerprint, aadhaarLastFour);
        }
        if (newPassword != null && !newPassword.isBlank()) {
            if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect.");
            }
            if (!newPassword.equals(confirmPassword) || newPassword.length() < 8 || newPassword.length() > 72) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New passwords must match and be 8 to 72 characters.");
            }
            user.setPasswordHash(passwordEncoder.encode(newPassword));
        }
        return user;
    }

    public UserAccount updateLocation(long userId, double latitude, double longitude) {
        UserAccount user = requireRole(userId, "DRIVER");
        if (!user.isOnline()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Go online before sharing your location.");
        }
        if (!validCoordinates(latitude, longitude)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter valid location coordinates.");
        }
        user.setLocation(latitude, longitude);
        rides.findByStatusOrderByCreatedAtAsc("NO_DRIVER_AVAILABLE").stream()
                .filter(ride -> vehicleClass(user.getVehicleType()) != null
                        && vehicleClass(user.getVehicleType()).equals(vehicleClass(ride.getVehicleType())))
                .filter(ride -> ride.getScheduledFor() == null || ride.getScheduledFor().isAfter(Instant.now()))
                .forEach(ride -> {
                    ride.setStatus("REQUESTED");
                    dispatch(ride);
                    if (!hasAvailableDriver(ride)) noDriverAvailable(ride);
                });
        return user;
    }

    public List<RideNotification> unreadNotifications(long userId) {
        UserAccount user = getUser(userId);
        List<RideNotification> unread = notifications.findTop20ByUserIdAndReadAtIsNullOrderByCreatedAtAsc(userId);
        unread.forEach(RideNotification::markRead);
        return unread;
    }

    @Scheduled(fixedRate = 60_000)
    public void processRideDeadlinesAndReminders() {
        Instant now = Instant.now();
        for (RideBooking ride : rides.findByStatusOrderByCreatedAtAsc("REQUESTED")) {
            if (ride.getScheduledFor() == null && ride.getCreatedAt().plus(IMMEDIATE_RIDE_TIMEOUT).isBefore(now)) {
                autoCancelNoDriverRide(ride);
            } else if (ride.getScheduledFor() != null && !ride.getScheduledFor().isAfter(now)) {
                noDriverAvailable(ride);
            } else if (ride.getScheduledFor() != null
                    && !ride.getScheduledFor().isAfter(now.plus(Duration.ofMinutes(15)))) {
                dispatch(ride);
                if (ride.getRedispatchedAt() == null
                        || ride.getRedispatchedAt().isBefore(now.minus(Duration.ofMinutes(14)))) {
                    notifyPendingDrivers(ride, "Scheduled ride pickup is in 15 minutes. Please respond now.");
                    ride.markRedispatched(now);
                }
            }
        }
        for (RideBooking ride : rides.findByStatusOrderByCreatedAtAsc("NO_DRIVER_AVAILABLE")) {
            Instant pickup = ride.getScheduledFor();
            if (pickup == null) {
                if (ride.getCreatedAt().plus(IMMEDIATE_RIDE_TIMEOUT).isBefore(now)) autoCancelNoDriverRide(ride);
                continue;
            }
            if (!pickup.isAfter(now)
                    || pickup.isAfter(now.plus(Duration.ofMinutes(15)))) continue;
            if (ride.getRedispatchedAt() != null
                    && ride.getRedispatchedAt().isAfter(now.minus(Duration.ofMinutes(14)))) continue;
            ride.setStatus("REQUESTED");
            dispatch(ride);
            ride.markRedispatched(now);
            if (!hasAvailableDriver(ride)) noDriverAvailable(ride);
        }
        for (RideBooking ride : rides.findByStatusOrderByCreatedAtAsc("REQUESTED")) {
            sendScheduledReminders(ride, now);
        }
        for (RideBooking ride : rides.findByStatusOrderByCreatedAtAsc("NO_DRIVER_AVAILABLE")) {
            sendScheduledReminders(ride, now);
        }
        for (RideBooking ride : rides.findByStatusOrderByCreatedAtAsc("ASSIGNED")) {
            sendScheduledReminders(ride, now);
        }
        for (UserAccount driver : users.findByRoleAndOnlineTrueOrderByIdAsc("DRIVER")) {
            if (driver.getLocationUpdatedAt() == null
                    || driver.getLocationUpdatedAt().isBefore(now.minus(MAX_DRIVER_LOCATION_AGE))) {
                driver.setOnline(false);
                notifyUser(driver, null, "You were set offline because live location updates stopped.");
            }
        }
        rides.findByStatusOrderByCreatedAtAsc("REQUESTED").stream()
                .filter(ride -> !hasAvailableDriver(ride))
                .forEach(this::noDriverAvailable);
    }

    private void sendScheduledReminders(RideBooking ride, Instant now) {
        Instant pickup = ride.getScheduledFor();
        if (pickup == null || ride.getStatus().equals("COMPLETED") || ride.getStatus().equals("CANCELLED")) return;
        Duration until = Duration.between(now, pickup);
        if (until.isNegative() || until.compareTo(Duration.ofMinutes(30)) > 0) return;
        // The scheduler runs once per minute, so use a small window around each
        // reminder target. Do not label every ride inside the first 20 minutes
        // as a 30-minute reminder (newly booked near-term rides hit that path).
        boolean thirtyMinuteWindow = until.compareTo(Duration.ofMinutes(29)) >= 0
                && until.compareTo(Duration.ofMinutes(31)) <= 0;
        boolean tenMinuteWindow = until.compareTo(Duration.ofMinutes(9)) >= 0
                && until.compareTo(Duration.ofMinutes(11)) <= 0;
        if (thirtyMinuteWindow && !ride.isRiderReminder30Sent()) {
            notifyUser(ride.getRider(), ride, "Reminder: your ride pickup is in about 30 minutes.");
            ride.markRiderReminder30Sent();
        }
        if (tenMinuteWindow && !ride.isRiderReminder10Sent()) {
            notifyUser(ride.getRider(), ride, "Reminder: your ride pickup is in about 10 minutes.");
            ride.markRiderReminder10Sent();
        }
        boolean hasDriver = ride.getDriver() != null;
        if (thirtyMinuteWindow && !ride.isDriverReminder30Sent()) {
            if (hasDriver) notifyUser(ride.getDriver(), ride, "Reminder: this scheduled pickup is in about 30 minutes.");
            else notifyPendingDrivers(ride, "Reminder: this scheduled pickup is in about 30 minutes.");
            ride.markDriverReminder30Sent();
        }
        if (tenMinuteWindow && !ride.isDriverReminder10Sent()) {
            if (hasDriver) notifyUser(ride.getDriver(), ride, "Reminder: this scheduled pickup is in about 10 minutes.");
            else notifyPendingDrivers(ride, "Reminder: this scheduled pickup is in about 10 minutes.");
            ride.markDriverReminder10Sent();
        }
    }

    private void noDriverAvailable(RideBooking ride) {
        if ("NO_DRIVER_AVAILABLE".equals(ride.getStatus())) return;
        ride.setStatus("NO_DRIVER_AVAILABLE");
        notifyUser(ride.getRider(), ride, "Driver not available. Try again or choose another vehicle.");
    }

    private void autoCancelNoDriverRide(RideBooking ride) {
        ride.setStatus("CANCELLED");
        notifyUser(ride.getRider(), ride,
                "Driver not available. This ride was cancelled. Try again or choose another vehicle.");
    }

    private boolean allMatchingDriversRejected(RideBooking ride) {
        String type = vehicleClass(ride.getVehicleType());
        if (type == null) return false;
        List<RideOffer> rideOffers = offers.findByRideIdAndStatus(ride.getId(), "REJECTED");
        boolean atLeastOneRejection = rideOffers.stream()
                .anyMatch(offer -> type.equals(vehicleClass(offer.getDriver().getVehicleType())));
        if (!atLeastOneRejection) return false;
        return offers.findByRideIdAndStatus(ride.getId(), "PENDING").stream()
                .noneMatch(offer -> isEligibleDriver(offer.getDriver(), ride));
    }

    private String driverType(String vehicleClass) {
        return switch (vehicleClass) {
            case "AUTO" -> "Auto";
            case "BIKE" -> "Bike";
            default -> "Mithila " + vehicleClass.substring("MITHILA ".length());
        };
    }

    private void notifyPendingDrivers(RideBooking ride, String message) {
        offers.findByRideIdAndStatus(ride.getId(), "PENDING")
                .forEach(offer -> notifyUser(offer.getDriver(), ride, message));
    }

    private void notifyUser(UserAccount user, RideBooking ride, String message) {
        notifications.save(new RideNotification(user, ride, message));
    }

    public List<RideComplaint> complaints(long userId) {
        return complaints.findByReporterIdOrderByCreatedAtDesc(userId);
    }

    public RideComplaint reportRide(long userId, long rideId, String category, String details) {
        UserAccount reporter = getUser(userId);
        RideBooking ride = rides.findById(rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found."));
        boolean participant = ride.getRider().getId().equals(userId)
                || (ride.getDriver() != null && ride.getDriver().getId().equals(userId));
        if (!participant) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only report your own rides.");
        if (!"COMPLETED".equals(ride.getStatus()) && !"CANCELLED".equals(ride.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Report an issue after the ride has ended.");
        }
        return complaints.save(new RideComplaint(reporter, ride, category.trim(), details.trim()));
    }

    public List<Landmark> landmarks() {
        return List.of(
                new Landmark("Madhubani Railway Station", "Station Road", 26.3543, 86.0710),
                new Landmark("Station Chowk", "Madhubani", 26.3594, 86.0718),
                new Landmark("Madhubani Bus Stand", "Bus Stand Road", 26.3650, 86.0732),
                new Landmark("Madhubani Market", "Main Market", 26.3611, 86.0756),
                new Landmark("Town Hall", "Madhubani", 26.3670, 86.0731),
                new Landmark("Nagar Nigam Office", "Madhubani", 26.3675, 86.0720),
                new Landmark("Rahika", "Rahika Block", 26.3749, 86.1200),
                new Landmark("BB Ganj", "Madhubani", 26.3630, 86.0830),
                new Landmark("Saurath", "Madhubani district", 26.4070, 86.0910),
                new Landmark("Pandaul", "Madhubani district", 26.2590, 86.1110),
                new Landmark("Rajnagar", "Madhubani district", 26.3710, 86.1520),
                new Landmark("Benipatti", "Madhubani district", 26.0480, 86.2780),
                new Landmark("Bisfi", "Madhubani district", 26.2470, 86.1350),
                new Landmark("Babubarhi", "Madhubani district", 26.5170, 86.0710),
                new Landmark("Khajauli", "Madhubani district", 26.4870, 86.1740),
                new Landmark("Phulparas", "Madhubani district", 26.3650, 86.4700),
                new Landmark("Andhratharhi", "Madhubani district", 26.2640, 86.3450),
                new Landmark("Madhepur", "Madhubani district", 26.4650, 86.4560),
                new Landmark("Ghoghardiha", "Madhubani district", 26.2670, 86.4980),
                new Landmark("Darbhanga Tower", "Darbhanga", 26.1542, 85.8918),
                new Landmark("Darbhanga Railway Station", "Darbhanga", 26.1667, 85.9000),
                new Landmark("Sitamarhi Railway Station", "Sitamarhi", 26.5960, 85.4910),
                new Landmark("Supaul Bus Stand", "Supaul", 26.1260, 86.6050),
                new Landmark("Saharsa Railway Station", "Saharsa", 25.8830, 86.6000),
                new Landmark("Samastipur Railway Station", "Samastipur", 25.8620, 85.7810),
                new Landmark("Jhanjharpur Railway Station", "Jhanjharpur", 26.2670, 86.2790),
                new Landmark("Laukaha Bazar", "Laukaha", 26.5570, 86.5450));
    }

    private void dispatch(RideBooking ride) {
        if (!"REQUESTED".equals(ride.getStatus()) && !"NO_DRIVER_AVAILABLE".equals(ride.getStatus())) return;
        String requestedVehicle = vehicleClass(ride.getVehicleType());
        if (requestedVehicle == null) return;
        users.findByRoleAndOnlineTrueOrderByIdAsc("DRIVER").stream()
                .filter(driver -> requestedVehicle.equals(vehicleClass(driver.getVehicleType()))
                        && isEligibleDriver(driver, ride))
                .filter(driver -> offers.findByRideIdAndDriverId(ride.getId(), driver.getId()).isEmpty())
                .forEach(driver -> {
                    ride.addOffer(new RideOffer(ride, driver));
                    if (ride.getScheduledFor() != null) {
                        notifyUser(driver, ride, "New scheduled ride: " + ride.getPickup() + " to " + ride.getDestination());
                    }
                });
        ride.markDispatched(Instant.now());
    }

    private boolean isEligibleDriver(UserAccount driver, RideBooking ride) {
        String rideVehicleClass = vehicleClass(ride.getVehicleType());
        if (!driver.isOnline() || rideVehicleClass == null
                || !rideVehicleClass.equals(vehicleClass(driver.getVehicleType()))) return false;
        if (ride.getPickupLatitude() == null || ride.getPickupLongitude() == null) return true;
        if (!hasFreshDriverLocation(driver)) return false;
        return haversineDistanceKm(driver.getLastLatitude(), driver.getLastLongitude(),
                ride.getPickupLatitude(), ride.getPickupLongitude()) <= dispatchRadiusKm;
    }

    private double haversineDistanceKm(double latitude1, double longitude1, double latitude2, double longitude2) {
        double latitudeDistance = Math.toRadians(latitude2 - latitude1);
        double longitudeDistance = Math.toRadians(longitude2 - longitude1);
        double a = Math.sin(latitudeDistance / 2) * Math.sin(latitudeDistance / 2)
                + Math.cos(Math.toRadians(latitude1)) * Math.cos(Math.toRadians(latitude2))
                * Math.sin(longitudeDistance / 2) * Math.sin(longitudeDistance / 2);
        return 6_371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private String vehicleClass(String vehicleType) {
        if (vehicleType == null || vehicleType.isBlank()) return null;
        String normalizedType = vehicleType.toUpperCase(Locale.ROOT);
        if (normalizedType.endsWith("AUTO")) return "AUTO";
        if (normalizedType.endsWith("BIKE")) return "BIKE";
        if (normalizedType.endsWith("E-RICKSHAW")) return "MITHILA E-RICKSHAW";
        if (normalizedType.endsWith("MINI")) return "MITHILA MINI";
        if (normalizedType.endsWith("SUV")) return "MITHILA SUV";
        return null;
    }

    private boolean validCoordinates(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    private boolean validCoordinatesPair(Double latitude, Double longitude) {
        return latitude != null && longitude != null
                && validCoordinates(latitude.doubleValue(), longitude.doubleValue());
    }

    private String normalizePhone(String phone) {
        String normalized = phone.replaceAll("[\\s()-]", "");
        if (normalized.startsWith("+91") && normalized.length() == 13) return normalized.substring(3);
        if (normalized.startsWith("91") && normalized.length() == 12) return normalized.substring(2);
        return normalized;
    }

    private String normalizeAadhaar(String aadhaarNumber) {
        String normalized = aadhaarNumber.trim();
        if (!normalized.matches("[0-9]{12}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid 12-digit Aadhaar number.");
        }
        return normalized;
    }

    private String fingerprintAadhaar(String aadhaarNumber) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(aadhaarHmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(aadhaarNumber.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable.", error);
        }
    }

    public record Landmark(String name, String area, double latitude, double longitude) { }
    public record VehicleAvailability(String vehicleType, long registeredDrivers, long onlineDrivers) { }

    private UserAccount requireRole(long userId, String role) {
        UserAccount user = getUser(userId);
        if (!role.equals(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This action is not available for your account.");
        }
        return user;
    }

    private String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!"RIDER".equals(normalized) && !"DRIVER".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose Rider or Driver.");
        }
        return normalized;
    }
}
