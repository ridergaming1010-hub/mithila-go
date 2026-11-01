package mithila.mith.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToMany;

@Entity
@Table(name = "ride_bookings")
public class RideBooking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false)
    @JoinColumn(name = "rider_id", nullable = false)
    private UserAccount rider;
    @ManyToOne
    @JoinColumn(name = "driver_id")
    private UserAccount driver;
    @Column(nullable = false, length = 160)
    private String pickup;
    @Column(nullable = false, length = 160)
    private String destination;
    @Column(nullable = false, length = 40)
    private String vehicleType;
    @Column(nullable = false)
    private int fare;
    @Column(nullable = false, length = 24)
    private String status;
    @Column(nullable = false, length = 6)
    private String otp;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant scheduledFor;
    private Double pickupLatitude;
    private Double pickupLongitude;
    private Double dropoffLatitude;
    private Double dropoffLongitude;
    @Column(columnDefinition = "float default 0")
    private Double distanceKm = 0.0;
    @Column(columnDefinition = "integer default 0")
    private Integer durationMinutes = 0;
    @Column(columnDefinition = "boolean default false")
    private Boolean approximateFare = false;
    private Instant lastDispatchAt;
    @Column(columnDefinition = "boolean default false")
    private Boolean riderReminder30Sent = false;
    @Column(columnDefinition = "boolean default false")
    private Boolean riderReminder10Sent = false;
    @Column(columnDefinition = "boolean default false")
    private Boolean driverReminder30Sent = false;
    @Column(columnDefinition = "boolean default false")
    private Boolean driverReminder10Sent = false;
    private Instant redispatchedAt;
    @OneToMany(mappedBy = "ride", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RideOffer> offers = new ArrayList<>();
    @Version
    private long version;

    protected RideBooking() { }

    public RideBooking(UserAccount rider, String pickup, String destination, String vehicleType, int fare, String otp) {
        this(rider, pickup, destination, vehicleType, fare, otp, null, null, null);
    }

    public RideBooking(UserAccount rider, String pickup, String destination, String vehicleType, int fare, String otp,
                       Instant scheduledFor, Double pickupLatitude, Double pickupLongitude) {
        this.rider = rider;
        this.pickup = pickup;
        this.destination = destination;
        this.vehicleType = vehicleType;
        this.fare = fare;
        this.otp = otp;
        this.status = "REQUESTED";
        this.createdAt = Instant.now();
        this.scheduledFor = scheduledFor;
        this.pickupLatitude = pickupLatitude;
        this.pickupLongitude = pickupLongitude;
    }

    public Long getId() { return id; }
    public UserAccount getRider() { return rider; }
    public UserAccount getDriver() { return driver; }
    public String getPickup() { return pickup; }
    public String getDestination() { return destination; }
    public String getVehicleType() { return vehicleType; }
    public int getFare() { return fare; }
    public String getStatus() { return status; }
    public String getOtp() { return otp; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getScheduledFor() { return scheduledFor; }
    public Double getPickupLatitude() { return pickupLatitude; }
    public Double getPickupLongitude() { return pickupLongitude; }
    public Double getDropoffLatitude() { return dropoffLatitude; }
    public Double getDropoffLongitude() { return dropoffLongitude; }
    public double getDistanceKm() { return distanceKm == null ? 0 : distanceKm; }
    public int getDurationMinutes() { return durationMinutes == null ? 0 : durationMinutes; }
    public boolean isApproximateFare() { return Boolean.TRUE.equals(approximateFare); }
    public Instant getLastDispatchAt() { return lastDispatchAt; }
    public boolean isRiderReminder30Sent() { return Boolean.TRUE.equals(riderReminder30Sent); }
    public boolean isRiderReminder10Sent() { return Boolean.TRUE.equals(riderReminder10Sent); }
    public boolean isDriverReminder30Sent() { return Boolean.TRUE.equals(driverReminder30Sent); }
    public boolean isDriverReminder10Sent() { return Boolean.TRUE.equals(driverReminder10Sent); }
    public Instant getRedispatchedAt() { return redispatchedAt; }
    public List<RideOffer> getOffers() { return offers; }
    public void addOffer(RideOffer offer) { offers.add(offer); }
    public void setPickupLocation(double latitude, double longitude) {
        this.pickupLatitude = latitude;
        this.pickupLongitude = longitude;
    }
    public void setDropoffLocation(double latitude, double longitude) {
        this.dropoffLatitude = latitude;
        this.dropoffLongitude = longitude;
    }
    public void applyFareQuote(double distanceKm, int durationMinutes, boolean approximateFare) {
        this.distanceKm = distanceKm;
        this.durationMinutes = durationMinutes;
        this.approximateFare = approximateFare;
    }
    public void assignTo(UserAccount driver) { this.driver = driver; this.status = "ASSIGNED"; }
    public void setStatus(String status) { this.status = status; }
    public void markDispatched(Instant at) { this.lastDispatchAt = at; }
    public void markRedispatched(Instant at) { this.redispatchedAt = at; }
    public void markRiderReminder30Sent() { this.riderReminder30Sent = true; }
    public void markRiderReminder10Sent() { this.riderReminder10Sent = true; }
    public void markDriverReminder30Sent() { this.driverReminder30Sent = true; }
    public void markDriverReminder10Sent() { this.driverReminder10Sent = true; }
}
