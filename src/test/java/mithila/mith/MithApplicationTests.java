package mithila.mith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.time.Instant;
import jakarta.servlet.http.Cookie;

import mithila.mith.model.RideBooking;
import mithila.mith.model.UserAccount;
import mithila.mith.service.RideService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
class MithApplicationTests {
	@DynamicPropertySource
	static void routeService(DynamicPropertyRegistry registry) {
		ApiIntegrationTestSupport.routeService(registry);
	}

	@Autowired
	private RideService rideService;
	@Autowired
	private MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	@Test
	void riderAndDriverCanCompleteRideOnlyWithCorrectOtp() {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		UserAccount rider = rideService.signup("Test Rider", suffix + "@example.com", randomPhone("9"),
				"strong-password", "RIDER", null, null);
		UserAccount driver = rideService.signup("Test Driver", "driver-" + suffix + "@example.com",
				randomPhone("8"), "strong-password", "DRIVER", "Auto", "BR-01-AB-1234");

		assertNotEquals("strong-password", rider.getPasswordHash());
		RideBooking ride = rideService.book(rider.getId(), "Town Hall", "Railway Station", "Mithila Auto",
				null, 26.36, 86.07, 26.3543, 86.0710);
		assertEquals("REQUESTED", ride.getStatus());
		rideService.setOnline(driver.getId(), true, 26.36, 86.07);
		ride = rideService.accept(driver.getId(), ride.getId());
		assertEquals("ASSIGNED", ride.getStatus());
		RideBooking assignedRide = ride;
		assertThrows(ResponseStatusException.class,
				() -> rideService.changeStatus(driver.getId(), assignedRide.getId(), "IN_PROGRESS", "0000"));
		ride = rideService.changeStatus(driver.getId(), ride.getId(), "IN_PROGRESS", ride.getOtp());
		assertEquals("IN_PROGRESS", ride.getStatus());
		rideService.updateLocation(driver.getId(), 26.3543, 86.0710);
		ride = rideService.changeStatus(driver.getId(), ride.getId(), "COMPLETED", null);
		assertEquals("COMPLETED", ride.getStatus());
	}

	@Test
	void dispatchOffersOnlyReachOnlineDriversWithTheMatchingVehicle() {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		UserAccount rider = rideService.signup("Dispatch Rider", suffix + "@example.com", randomPhone("9"),
				"strong-password", "RIDER", null, null);
		UserAccount auto = rideService.signup("Auto Driver", "auto-" + suffix + "@example.com", randomPhone("8"),
				"strong-password", "DRIVER", "Auto", "BR-02-AB-1234");
		UserAccount bike = rideService.signup("Bike Driver", "bike-" + suffix + "@example.com", randomPhone("7"),
				"strong-password", "DRIVER", "Bike", "BR-03-AB-1234");
		rideService.setOnline(auto.getId(), true, 26.36, 86.07);
		rideService.setOnline(bike.getId(), true, 26.36, 86.07);

		rideService.book(rider.getId(), "Madhubani Station", "Rahika", "Mithila Bike", null,
				26.3543, 86.0710, 26.3749, 86.1200);

		assertEquals(0, rideService.driverOffers(auto.getId()).size());
		assertEquals(1, rideService.driverOffers(bike.getId()).size());
	}

	@Test
	void scheduledRideIsVisibleToDriversAsSoonAsItIsBooked() {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		UserAccount rider = rideService.signup("Scheduled Rider", suffix + "@example.com", randomPhone("9"),
				"strong-password", "RIDER", null, null);
		UserAccount driver = rideService.signup("Scheduled Auto", "scheduled-" + suffix + "@example.com",
				randomPhone("8"), "strong-password", "DRIVER", "Auto", "BR-04-AB-1234");
		rideService.setOnline(driver.getId(), true, 26.36, 86.07);
		RideBooking ride = rideService.book(rider.getId(), "Station Chowk", "Rahika", "Mithila Auto",
				Instant.now().plusSeconds(10 * 60), 26.3594, 86.0718, 26.3749, 86.1200);

		assertEquals(true, rideService.driverOffers(driver.getId()).stream()
				.anyMatch(offer -> offer.getId().equals(ride.getId())));
	}

	@Test
	void registrationRejectsMismatchedPasswordsAndNonIndianLengthPhoneNumbers() {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		assertThrows(org.springframework.web.server.ResponseStatusException.class,
				() -> rideService.signup("Mismatch", suffix + "@example.com", randomPhone("9"),
						"first-password", "different-password", "RIDER", null, null));
		assertThrows(org.springframework.web.server.ResponseStatusException.class,
				() -> rideService.signup("Invalid Phone", suffix + "-phone@example.com", "12345",
						"strong-password", "strong-password", "RIDER", null, null));
	}

	@Test
	void countryCodePhonesAreStoredAndAuthenticatedInCanonicalFormat() {
		String phone = randomPhone("9");
		UserAccount rider = rideService.signup("Country Code Rider", UUID.randomUUID() + "@example.com",
				"+91 " + phone, "strong-password", "RIDER", null, null);

		assertEquals(phone, rider.getPhone());
		assertEquals(rider.getId(), rideService.login("+91 " + phone, "strong-password", "RIDER").getId());
	}

	@Test
	void roleRestrictedRideEndpointsRequireAnAuthenticatedSession() throws Exception {
		mockMvc.perform(get("/api/rides")).andExpect(status().isUnauthorized());

		String suffix = UUID.randomUUID().toString().replace("-", "");
		UserAccount rider = rideService.signup("API Rider", "api-" + suffix + "@example.com", randomPhone("6"),
				"strong-password", "RIDER", null, null);
		MvcResult login = mockMvc.perform(post("/api/login").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"identifier\":\"" + rider.getEmail()
								+ "\",\"password\":\"strong-password\",\"role\":\"RIDER\"}"))
				.andExpect(status().isOk())
				.andReturn();

		mockMvc.perform(get("/api/rides").session((org.springframework.mock.web.MockHttpSession)
						login.getRequest().getSession(false)))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/driver/rides").session((org.springframework.mock.web.MockHttpSession)
						login.getRequest().getSession(false)))
				.andExpect(status().isForbidden());
	}

	@Test
	void browserCanFetchCsrfCookieBeforeSigningIn() throws Exception {
		var response = mockMvc.perform(get("/api/csrf"))
				.andExpect(status().isOk())
				.andExpect(cookie().exists("XSRF-TOKEN"))
				.andReturn()
				.getResponse();
		Cookie csrfCookie = response.getCookie("XSRF-TOKEN");
		String suffix = UUID.randomUUID().toString().replace("-", "");
		UserAccount rider = rideService.signup("CSRF Rider", "csrf-" + suffix + "@example.com",
				randomPhone("5"), "strong-password", "RIDER", null, null);

		mockMvc.perform(post("/api/login")
						.cookie(csrfCookie)
						.header("X-XSRF-TOKEN", csrfCookie.getValue())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"identifier\":\"" + rider.getEmail()
								+ "\",\"password\":\"strong-password\",\"role\":\"RIDER\"}"))
				.andExpect(status().isOk());
	}

	@Test
	void riderCanRegisterWithoutDriverFields() throws Exception {
		String suffix = UUID.randomUUID().toString().replace("-", "");

		mockMvc.perform(post("/api/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Signup Rider\",\"email\":\"signup-" + suffix
								+ "@example.com\",\"phone\":\"" + randomPhone("4")
								+ "\",\"password\":\"strong-password\",\"confirmPassword\":\"strong-password\","
								+ "\"role\":\"RIDER\"}"))
				.andExpect(status().isOk());
	}

	private String randomPhone(String firstDigit) {
		return firstDigit + ThreadLocalRandom.current().nextInt(100_000_000, 1_000_000_000);
	}

}
