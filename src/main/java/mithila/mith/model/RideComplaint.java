package mithila.mith.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "ride_complaints")
public class RideComplaint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private UserAccount reporter;
    @ManyToOne(optional = false)
    @JoinColumn(name = "ride_id", nullable = false)
    private RideBooking ride;
    @Column(nullable = false, length = 40)
    private String category;
    @Column(nullable = false, length = 2000)
    private String details;
    @Column(nullable = false, length = 20)
    private String status = "OPEN";
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected RideComplaint() { }

    public RideComplaint(UserAccount reporter, RideBooking ride, String category, String details) {
        this.reporter = reporter;
        this.ride = ride;
        this.category = category;
        this.details = details;
    }

    public Long getId() { return id; }
    public RideBooking getRide() { return ride; }
    public String getCategory() { return category; }
    public String getDetails() { return details; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
