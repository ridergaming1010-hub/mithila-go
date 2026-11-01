package mithila.mith.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_accounts")
public class UserAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, unique = true, length = 150)
    private String email;
    @Column(nullable = false, unique = true, length = 20)
    private String phone;
    @Column(nullable = false, length = 100)
    private String passwordHash;
    @Column(nullable = false, length = 10)
    private String role;
    @Column(length = 20)
    private String vehiclePlate;
    @Column(length = 40)
    private String vehicleType;
    @Column(nullable = false)
    private boolean online;
    @Column(length = 4)
    private String aadhaarLastFour;
    @Column(unique = true, length = 64)
    private String aadhaarFingerprint;
    @Column(length = 500)
    private String profileImageUrl;
    private Double lastLatitude;
    private Double lastLongitude;
    private java.time.Instant locationUpdatedAt;

    protected UserAccount() { }

    public UserAccount(String name, String email, String phone, String passwordHash, String role,
                       String vehiclePlate, String vehicleType) {
        this(name, email, phone, passwordHash, role, vehiclePlate, vehicleType, null);
    }

    public UserAccount(String name, String email, String phone, String passwordHash, String role,
                       String vehiclePlate, String vehicleType, String aadhaarLastFour) {
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.role = role;
        this.vehiclePlate = vehiclePlate;
        this.vehicleType = vehicleType;
        this.aadhaarLastFour = aadhaarLastFour;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getPasswordHash() { return passwordHash; }
    public String getRole() { return role; }
    public String getVehiclePlate() { return vehiclePlate; }
    public String getVehicleType() { return vehicleType; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) {
        this.online = online;
        if (!online) {
            this.lastLatitude = null;
            this.lastLongitude = null;
            this.locationUpdatedAt = null;
        }
    }
    public String getAadhaarLastFour() { return aadhaarLastFour; }
    public String getAadhaarFingerprint() { return aadhaarFingerprint; }
    public String getProfileImageUrl() { return profileImageUrl; }
    public Double getLastLatitude() { return lastLatitude; }
    public Double getLastLongitude() { return lastLongitude; }
    public java.time.Instant getLocationUpdatedAt() { return locationUpdatedAt; }
    public void updateProfile(String name, String phone, String profileImageUrl) {
        this.name = name;
        this.phone = phone;
        this.profileImageUrl = profileImageUrl;
    }
    public void updateDriverProfile(String vehicleType, String vehiclePlate,
                                    String aadhaarFingerprint, String aadhaarLastFour) {
        this.vehicleType = vehicleType;
        this.vehiclePlate = vehiclePlate;
        if (aadhaarFingerprint != null) {
            this.aadhaarFingerprint = aadhaarFingerprint;
            this.aadhaarLastFour = aadhaarLastFour;
        }
    }
    public void setAadhaarFingerprint(String aadhaarFingerprint) { this.aadhaarFingerprint = aadhaarFingerprint; }
    public void setLocation(double latitude, double longitude) {
        this.lastLatitude = latitude;
        this.lastLongitude = longitude;
        this.locationUpdatedAt = java.time.Instant.now();
    }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void clearLocation() {
        this.lastLatitude = null;
        this.lastLongitude = null;
        this.locationUpdatedAt = null;
    }
}
