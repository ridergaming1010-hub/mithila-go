package mithila.mith.repository;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

import mithila.mith.model.RideBooking;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface RideBookingRepository extends JpaRepository<RideBooking, Long> {
    List<RideBooking> findByRiderIdOrderByCreatedAtDesc(Long riderId);
    List<RideBooking> findByStatusOrderByCreatedAtAsc(String status);
    @Query("select ride from RideBooking ride where ride.status = 'REQUESTED' "
            + "and (ride.scheduledFor is null or ride.scheduledFor <= :now)")
    List<RideBooking> findDispatchableRides(@Param("now") Instant now);
    List<RideBooking> findByDriverIdOrderByCreatedAtDesc(Long driverId);
    Optional<RideBooking> findByIdAndRiderId(Long id, Long riderId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ride from RideBooking ride where ride.id = :rideId")
    Optional<RideBooking> findByIdForUpdate(@Param("rideId") Long rideId);
}