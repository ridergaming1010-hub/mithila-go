package mithila.mith.config;

import mithila.mith.model.UserAccount;
import mithila.mith.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("development")
@ConditionalOnProperty(prefix = "mithila.demo.drivers", name = "enabled", havingValue = "true")
public class DemoDriverInitializer implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(DemoDriverInitializer.class);
    private final UserAccountRepository users;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final String autoPassword;
    private final String bikePassword;

    public DemoDriverInitializer(UserAccountRepository users,
            @Value("${mithila.demo.drivers.auto-password:}") String autoPassword,
            @Value("${mithila.demo.drivers.bike-password:}") String bikePassword) {
        this.users = users;
        this.autoPassword = autoPassword;
        this.bikePassword = bikePassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (autoPassword.length() < 8 || bikePassword.length() < 8) {
            throw new IllegalStateException(
                    "Development demo drivers require MITHILA_DEMO_DRIVERS_AUTO_PASSWORD and "
                            + "MITHILA_DEMO_DRIVERS_BIKE_PASSWORD (at least 8 characters each).");
        }
        createDriver("demo-auto@mithila.local", "9000000001", "Mithila Auto Demo", "Auto",
                "BR32AA0001", autoPassword);
        createDriver("demo-bike@mithila.local", "9000000002", "Mithila Bike Demo", "Bike",
                "BR32AB0002", bikePassword);
    }

    private void createDriver(String email, String phone, String name, String vehicleType,
                             String vehiclePlate, String password) {
        if (users.existsByEmailIgnoreCase(email) || users.existsByPhone(phone)) return;
        users.save(new UserAccount(name, email, phone, passwordEncoder.encode(password), "DRIVER",
                vehiclePlate, vehicleType));
        logger.info("Created development-only {} driver. Sign in as {} using the password configured in your environment.",
                vehicleType, email);
    }
}
