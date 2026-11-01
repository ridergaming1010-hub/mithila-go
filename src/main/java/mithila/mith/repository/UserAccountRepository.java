package mithila.mith.repository;

import java.util.List;
import java.util.Optional;

import mithila.mith.model.UserAccount;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByPhone(String phone);
    boolean existsByPhoneAndIdNot(String phone, Long id);
    boolean existsByAadhaarFingerprintAndIdNot(String aadhaarFingerprint, Long id);
    Optional<UserAccount> findByEmailIgnoreCaseOrPhone(String email, String phone);
    Optional<UserAccount> findByAadhaarFingerprint(String aadhaarFingerprint);
    List<UserAccount> findByRoleAndOnlineTrueOrderByIdAsc(String role);
    long countByRoleAndVehicleTypeIgnoreCase(String role, String vehicleType);
    List<UserAccount> findByRoleAndOnlineTrueAndVehicleTypeIgnoreCase(String role, String vehicleType);
    List<UserAccount> findByRoleAndVehicleTypeIgnoreCase(String role, String vehicleType);
}
