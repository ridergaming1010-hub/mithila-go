package mithila.mith;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountProfileIntegrationTests extends ApiIntegrationTestSupport {
    @Test
    void driverCanRegisterAndUpdateERickshawVehicleDetails() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String email = "e-rickshaw-" + suffix + "@example.com";
        String phone = randomPhone("8");

        mockMvc.perform(post("/api/signup").with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"name":"E-Rickshaw Driver","email":"%s","phone":"%s",
                                 "password":"%s","confirmPassword":"%s","role":"DRIVER",
                                 "vehicleType":"Mithila E-Rickshaw","vehiclePlate":"BR-01-EF-5678"}
                                """.formatted(email, phone, TEST_PASSWORD, TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleType").value("Mithila E-Rickshaw"));

        var driver = rideService.login(email, TEST_PASSWORD, "DRIVER");
        MockHttpSession session = login(driver);
        mockMvc.perform(put("/api/profile").with(csrf()).session(session)
                        .contentType("application/json")
                        .content("""
                                {"name":"E-Rickshaw Driver","phone":"%s",
                                 "vehicleType":"Mithila E-Rickshaw","vehiclePlate":"BR-01-EF-9012"}
                                """.formatted(phone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleType").value("Mithila E-Rickshaw"))
                .andExpect(jsonPath("$.vehiclePlate").value("BR-01-EF-9012"));
    }

    @Test
    void driverCanSignInWithAadhaarAndReplaceTheLinkedNumberWithoutExposingIt() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String email = "aadhaar-" + suffix + "@example.com";
        String phone = randomPhone("8");

        mockMvc.perform(post("/api/signup").with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"name":"Aadhaar Driver","email":"%s","phone":"%s",
                                 "password":"%s","confirmPassword":"%s","role":"DRIVER",
                                 "vehicleType":"Bike","vehiclePlate":"BR-01-CD-5678",
                                 "aadhaarNumber":"123456789012"}
                                """.formatted(email, phone, TEST_PASSWORD, TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aadhaarLastFour").value("9012"))
                .andExpect(jsonPath("$.aadhaarLoginEnabled").value(true))
                .andExpect(jsonPath("$.aadhaarFingerprint").doesNotExist());

        var driver = rideService.login(email, TEST_PASSWORD, "DRIVER");
        org.junit.jupiter.api.Assertions.assertNotEquals("123456789012", driver.getAadhaarFingerprint());
        MockHttpSession session = login(driver);

        mockMvc.perform(put("/api/profile").with(csrf()).session(session)
                        .contentType("application/json")
                        .content("""
                                {"name":"Aadhaar Driver","phone":"%s","vehicleType":"Auto",
                                 "vehiclePlate":"BR-01-EF-9012","aadhaarNumber":"987654321098"}
                                """.formatted(phone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleType").value("Auto"))
                .andExpect(jsonPath("$.vehiclePlate").value("BR-01-EF-9012"))
                .andExpect(jsonPath("$.aadhaarLastFour").value("1098"))
                .andExpect(jsonPath("$.aadhaarLoginEnabled").value(true))
                .andExpect(jsonPath("$.aadhaarFingerprint").doesNotExist());

        mockMvc.perform(post("/api/login").with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"identifier":"987654321098","password":"%s","role":"DRIVER"}
                                """.formatted(TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Aadhaar Driver"))
                .andExpect(jsonPath("$.aadhaarLastFour").value("1098"));
    }

    @Test
    void driverCanSignUpUpdateProfileChangePasswordAndSignOut() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String email = "portal-" + suffix + "@example.com";
        String phone = randomPhone("8");

        mockMvc.perform(post("/api/signup").with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"name":"Portal Driver","email":"%s","phone":"%s",
                                 "password":"%s","confirmPassword":"%s","role":"DRIVER",
                                 "vehicleType":"Auto","vehiclePlate":"BR-01-AB-1234"}
                                """.formatted(email, phone, TEST_PASSWORD, TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("DRIVER"))
                .andExpect(jsonPath("$.vehicleType").value("Auto"));

        var driver = rideService.login(email, TEST_PASSWORD, "DRIVER");
        MockHttpSession session = login(driver);
        mockMvc.perform(get("/api/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
        mockMvc.perform(put("/api/profile").with(csrf()).session(session)
                        .contentType("application/json")
                        .content("""
                                {"name":"Portal Driver Updated","phone":"%s",
                                 "profileImageUrl":"https://example.com/driver.png"}
                                """.formatted(phone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Portal Driver Updated"))
                .andExpect(jsonPath("$.profileImageUrl").value("https://example.com/driver.png"));

        mockMvc.perform(put("/api/profile").with(csrf()).session(session)
                        .contentType("application/json")
                        .content("""
                                {"name":"Portal Driver Updated","phone":"%s",
                                 "currentPassword":"%s","newPassword":"new-strong-password",
                                 "confirmPassword":"new-strong-password"}
                                """.formatted(phone, TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").value("https://example.com/driver.png"));
        mockMvc.perform(post("/api/logout").with(csrf()).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/me").session(session))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/login").with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"identifier":"%s","password":"new-strong-password","role":"DRIVER"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Portal Driver Updated"));
    }
}
