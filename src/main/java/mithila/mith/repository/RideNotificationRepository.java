package mithila.mith.repository;

import java.util.List;

import mithila.mith.model.RideNotification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideNotificationRepository extends JpaRepository<RideNotification, Long> {
    List<RideNotification> findTop20ByUserIdAndReadAtIsNullOrderByCreatedAtAsc(Long userId);
}
