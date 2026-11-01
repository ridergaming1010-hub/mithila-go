package mithila.mith.controller;

import java.time.Instant;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import mithila.mith.model.RideBooking;
import mithila.mith.model.UserAccount;
import mithila.mith.service.RideService;
import mithila.mith.service.RouteFareService;
import mithila.mith.service.LocationLookupService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.web.csrf.CsrfToken;

@RestController
@RequestMapping("/api")
@Transactional
public class RideController {
    private final RideService service;
    private final LocationLookupService locationLookupService;

    public RideController(RideService service, LocationLookupService locationLookupService) {
        this.service = service;
        this.locationLookupService = locationLookupService;
    }

    @PostMapping("/signup")
    public UserResponse signup(@Valid @RequestBody SignupRequest request) {
        UserAccount account = service.signup(request.name(), request.email(), request.phone(), request.password(),
            request.confirmPassword(), request.role(), request.vehicleType(), request.vehiclePlate(),
            request.aadhaarLastFour(), request.aadhaarNumber());
        return UserResponse.from(account);
    }

    @PostMapping("/login")
    public UserResponse login(@Valid @RequestBody LoginRequest request, HttpSession session,
                              HttpServletRequest httpRequest) {
        UserAccount account = service.login(request.identifier(), request.password(), request.role());
        httpRequest.changeSessionId();
        session.setAttribute("userId", account.getId());
        return UserResponse.from(account);
    }

    @PostMapping("/logout")
    public void logout(HttpSession session) {
        session.invalidate();
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(HttpSession session) {
        Object id = session.getAttribute("userId");
        if (!(id instanceof Long userId)) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(UserResponse.from(service.getUser(userId)));
    }

    @GetMapping("/estimate")
    public FareResponse estimate(@RequestParam String vehicleType,
                                 @RequestParam(required = false) Double pickupLatitude,
                                 @RequestParam(required = false) Double pickupLongitude,
                                 @RequestParam(required = false) Double dropoffLatitude,
                                 @RequestParam(required = false) Double dropoffLongitude) {
        try {
            RouteFareService.FareQuote quote = service.quoteFare(vehicleType, pickupLatitude, pickupLongitude,
                    dropoffLatitude, dropoffLongitude);
            return new FareResponse(quote.fare(), quote.distanceKm(), quote.durationMinutes(), quote.approximate(), quote.routeCoordinates());
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }

    @GetMapping("/vehicles")
    public List<RideService.VehicleAvailability> vehicles() {
        return service.vehicleAvailability();
    }

    @GetMapping("/notifications")
    public List<NotificationResponse> notifications(HttpSession session) {
        return service.unreadNotifications(userId(session)).stream()
                .map(item -> new NotificationResponse(item.getId(), item.getRideId(), item.getMessage(), item.getCreatedAt()))
                .toList();
    }

    @GetMapping("/landmarks")
    public List<LandmarkResponse> landmarks() {
        return service.landmarks().stream()
                .map(place -> new LandmarkResponse(place.name(), place.area(), place.latitude(), place.longitude()))
                .toList();
    }

    @GetMapping("/geocode/reverse")
    public LocationResponse reverseGeocode(@RequestParam double latitude, @RequestParam double longitude) {
        LocationLookupService.LocationResult location = locationLookupService.reverse(latitude, longitude);
        return new LocationResponse(location.name(), location.area(), location.latitude(), location.longitude());
    }

    @GetMapping("/geocode/search")
    public List<LocationResponse> searchLocations(
            @RequestParam @NotBlank @Size(max = 120) String query,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude) {
        return locationLookupService.search(query, latitude, longitude).stream()
                .map(location -> new LocationResponse(location.name(), location.area(),
                        location.latitude(), location.longitude()))
                .toList();
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/book-ride")
    public RideResponse bookRide(@Valid @RequestBody BookRideRequest request, HttpSession session) {
        RideBooking ride = service.book(userId(session), request.pickup(), request.destination(), request.vehicleType(),
                request.scheduledFor(), request.pickupLatitude(), request.pickupLongitude(),
                request.dropoffLatitude(), request.dropoffLongitude());
        return rideResponse(ride, false, service.hasAvailableDriver(ride));
    }

    @GetMapping("/rides")
    public List<RideResponse> riderHistory(HttpSession session) {
        return service.riderHistory(userId(session)).stream()
            .map(ride -> rideResponse(ride,
                "ASSIGNED".equals(ride.getStatus()) || "IN_PROGRESS".equals(ride.getStatus()),
                service.hasAvailableDriver(ride)))
            .toList();
    }

    @GetMapping("/driver/rides")
    public List<RideResponse> driverOffers(HttpSession session) {
        return service.driverOffers(userId(session)).stream()
            .map(ride -> rideResponse(ride, false, service.hasAvailableDriver(ride)))
            .toList();
    }

    @PostMapping("/driver/rides/{rideId}/accept")
    public RideResponse accept(@PathVariable long rideId, HttpSession session) {
        RideBooking ride = service.accept(userId(session), rideId);
        return rideResponse(ride, false, service.hasAvailableDriver(ride));
    }

    @PostMapping("/driver/rides/{rideId}/reject")
    public RideResponse reject(@PathVariable long rideId, HttpSession session) {
        RideBooking ride = service.reject(userId(session), rideId);
        return rideResponse(ride, false, service.hasAvailableDriver(ride));
    }

    @PutMapping("/driver/online")
    public UserResponse setOnline(@RequestBody OnlineRequest request, HttpSession session) {
        return UserResponse.from(service.setOnline(userId(session), request.online(), request.latitude(), request.longitude()));
    }

    @PutMapping("/driver/location")
    public UserResponse setDriverLocation(@Valid @RequestBody LocationRequest request, HttpSession session) {
        UserAccount account = service.updateLocation(userId(session), request.latitude(), request.longitude());
        return UserResponse.from(account);
    }

    @PutMapping("/profile")
    public UserResponse updateProfile(@Valid @RequestBody UpdateProfileRequest request, HttpSession session) {
        return UserResponse.from(service.updateProfile(userId(session), request.name(), request.phone(),
                request.profileImageUrl(), request.currentPassword(), request.newPassword(), request.confirmPassword(),
                request.vehicleType(), request.vehiclePlate(), request.aadhaarNumber()));
    }

    @GetMapping("/complaints")
    public List<ComplaintResponse> complaints(HttpSession session) {
        return service.complaints(userId(session)).stream()
                .map(complaint -> new ComplaintResponse(complaint.getId(), complaint.getRide().getId(),
                        complaint.getCategory(), complaint.getDetails(), complaint.getStatus(),
                        complaint.getCreatedAt()))
                .toList();
    }

    @PostMapping("/complaints")
    public ComplaintResponse reportRide(@Valid @RequestBody ComplaintRequest request, HttpSession session) {
        var complaint = service.reportRide(userId(session), request.rideId(), request.category(), request.details());
        return new ComplaintResponse(complaint.getId(), complaint.getRide().getId(), complaint.getCategory(),
                complaint.getDetails(), complaint.getStatus(), complaint.getCreatedAt());
    }

    @PatchMapping("/rides/{rideId}/status")
    public RideResponse changeStatus(@PathVariable long rideId, @RequestParam String status,
                                     @RequestParam(required = false) String otp, HttpSession session) {
        long id = userId(session);
        RideBooking ride = service.changeStatus(id, rideId, status.trim().toUpperCase(), otp);
        return rideResponse(ride, "RIDER".equals(service.getUser(id).getRole()), service.hasAvailableDriver(ride));
    }

    private RideResponse rideResponse(RideBooking ride, boolean revealOtp, boolean driverAvailable) {
        return RideResponse.from(ride, revealOtp, driverAvailable,
                service.hasFreshDriverLocation(ride.getDriver()));
    }

    private long userId(HttpSession session) {
        Object id = session.getAttribute("userId");
        if (!(id instanceof Long userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in to continue.");
        }
        return userId;
    }

    public record SignupRequest(
            @NotBlank(message = "Enter your name.") @Size(max = 100) String name,
            @NotBlank(message = "Enter a valid email.") @Email @Size(max = 150) String email,
            @NotBlank(message = "Enter your phone number.") @Pattern(regexp = "^[+0-9()\\s-]{7,20}$", message = "Enter a valid phone number.") String phone,
            @NotBlank(message = "Choose a password.") @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters.") String password,
            @Size(max = 72) String confirmPassword,
            @NotBlank(message = "Choose Rider or Driver.") String role,
            @Size(max = 40) String vehicleType,
            @Size(max = 20) String vehiclePlate,
            @Size(max = 4) String aadhaarLastFour,
            @Size(max = 12) String aadhaarNumber) { }

    public record LoginRequest(@NotBlank String identifier, @NotBlank String password, @NotBlank String role) { }

    public record BookRideRequest(@NotBlank @Size(max = 160) String pickup,
                                  @NotBlank @Size(max = 160) String destination,
                                  @NotBlank String vehicleType, Instant scheduledFor,
                                  Double pickupLatitude, Double pickupLongitude,
                                  Double dropoffLatitude, Double dropoffLongitude) { }

    public record OnlineRequest(boolean online, Double latitude, Double longitude) { }
    public record LocationRequest(
            @jakarta.validation.constraints.DecimalMin("-90") @jakarta.validation.constraints.DecimalMax("90")
            double latitude,
            @jakarta.validation.constraints.DecimalMin("-180") @jakarta.validation.constraints.DecimalMax("180")
            double longitude) { }
    public record FareResponse(int fare, double distanceKm, int durationMinutes, boolean approximate,
                               List<List<Double>> routeCoordinates) { }
    public record LandmarkResponse(String name, String area, double latitude, double longitude) { }
    public record LocationResponse(String name, String area, double latitude, double longitude) { }
    public record CsrfResponse(String headerName, String token) { }
    public record UpdateProfileRequest(@NotBlank @Size(max = 100) String name,
                                       @NotBlank @Pattern(regexp = "^[+0-9()\\s-]{7,20}$") String phone,
                                       @Size(max = 500) String profileImageUrl,
                                       String currentPassword,
                                       @Size(max = 72) String newPassword,
                                       @Size(max = 72) String confirmPassword,
                                       @Size(max = 40) String vehicleType,
                                       @Size(max = 20) String vehiclePlate,
                                       @Size(max = 12) String aadhaarNumber) { }
    public record ComplaintRequest(@jakarta.validation.constraints.Positive long rideId,
                                   @NotBlank @Size(max = 40) String category,
                                   @NotBlank @Size(max = 2000) String details) { }
    public record ComplaintResponse(Long id, Long rideId, String category, String details,
                                    String status, Instant createdAt) { }
    public record NotificationResponse(Long id, Long rideId, String message, Instant createdAt) { }

    public record UserResponse(Long id, String name, String email, String phone, String role,
                               String vehicleType, String vehiclePlate, boolean online,
                               boolean aadhaarLoginEnabled, String aadhaarLastFour, String profileImageUrl,
                               Double lastLatitude, Double lastLongitude) {
        static UserResponse from(UserAccount account) {
            return new UserResponse(account.getId(), account.getName(), account.getEmail(), account.getPhone(),
                    account.getRole(), account.getVehicleType(), account.getVehiclePlate(), account.isOnline(),
                    account.getAadhaarFingerprint() != null, account.getAadhaarLastFour(), account.getProfileImageUrl(),
                    account.getLastLatitude(), account.getLastLongitude());
        }
    }

    public record RideResponse(Long id, String pickup, String destination, String vehicleType, int fare,
                               String status, String otp, Instant createdAt, Instant scheduledFor, String driverName,
                               String driverVehicle, String riderName,
                               Double driverLatitude, Double driverLongitude, Double pickupLatitude,
                               Double pickupLongitude, Double dropoffLatitude, Double dropoffLongitude,
                               boolean driverAvailable, double distanceKm, int durationMinutes, boolean approximateFare) {
        static RideResponse from(RideBooking ride, boolean includeOtp, boolean driverAvailable,
                                  boolean freshDriverLocation) {
            UserAccount driver = ride.getDriver();
            boolean live = freshDriverLocation
                    && ("ASSIGNED".equals(ride.getStatus()) || "IN_PROGRESS".equals(ride.getStatus()));
            return new RideResponse(ride.getId(), ride.getPickup(), ride.getDestination(), ride.getVehicleType(),
                    ride.getFare(), ride.getStatus(), includeOtp ? ride.getOtp() : null, ride.getCreatedAt(),
                    ride.getScheduledFor(),
                    driver == null ? null : driver.getName(),
                    driver == null ? null : driver.getVehicleType() + " • " + driver.getVehiclePlate(),
                    ride.getRider().getName(),
                    !live || driver == null ? null : driver.getLastLatitude(),
                    !live || driver == null ? null : driver.getLastLongitude(),
                    ride.getPickupLatitude(), ride.getPickupLongitude(),
                    ride.getDropoffLatitude(), ride.getDropoffLongitude(), driverAvailable,
                    ride.getDistanceKm(), ride.getDurationMinutes(), ride.isApproximateFare());
        }
    }
}
