package mithila.mith.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "ride_notifications")
public class RideNotification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;
    @ManyToOne
    @JoinColumn(name = "ride_id")
    private RideBooking ride;
    @Column(nullable = false, length = 240)
    private String message;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    private Instant readAt;

    protected RideNotification() { }

    public RideNotification(UserAccount user, RideBooking ride, String message) {
        this.user = user;
        this.ride = ride;
        this.message = message;
    }

    public Long getId() { return id; }
    public Long getRideId() { return ride == null ? null : ride.getId(); }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
    public void markRead() { this.readAt = Instant.now(); }
}
