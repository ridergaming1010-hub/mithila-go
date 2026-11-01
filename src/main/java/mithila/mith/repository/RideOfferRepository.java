package mithila.mith.repository;

import java.util.List;
import java.util.Optional;

import mithila.mith.model.RideOffer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideOfferRepository extends JpaRepository<RideOffer, Long> {
    List<RideOffer> findByDriverIdAndStatusOrderByRideCreatedAtDesc(Long driverId, String status);
    Optional<RideOffer> findByRideIdAndDriverId(Long rideId, Long driverId);
    List<RideOffer> findByRideIdAndStatus(Long rideId, String status);
}
