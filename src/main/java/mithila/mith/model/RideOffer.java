package mithila.mith.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "ride_offers", uniqueConstraints = @UniqueConstraint(columnNames = {"ride_id", "driver_id"}))
public class RideOffer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false)
    @JoinColumn(name = "ride_id", nullable = false)
    private RideBooking ride;
    @ManyToOne(optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private UserAccount driver;
    @Column(nullable = false, length = 10)
    private String status = "PENDING";

    protected RideOffer() { }

    public RideOffer(RideBooking ride, UserAccount driver) {
        this.ride = ride;
        this.driver = driver;
    }

    public RideBooking getRide() { return ride; }
    public UserAccount getDriver() { return driver; }
    public String getStatus() { return status; }
    public void accept() { status = "ACCEPTED"; }
    public void reject() { status = "REJECTED"; }
}
