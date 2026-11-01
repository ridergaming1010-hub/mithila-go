package mithila.mith.repository;

import java.util.List;
import mithila.mith.model.RideComplaint;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideComplaintRepository extends JpaRepository<RideComplaint, Long> {
    List<RideComplaint> findByReporterIdOrderByCreatedAtDesc(Long reporterId);
}
