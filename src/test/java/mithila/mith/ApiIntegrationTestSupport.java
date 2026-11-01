package mithila.mith;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import mithila.mith.model.UserAccount;
import mithila.mith.service.RideService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class ApiIntegrationTestSupport {
    protected static final String TEST_PASSWORD = "strong-password";
    private static HttpServer routeServer;

    @DynamicPropertySource
    static void routeService(DynamicPropertyRegistry registry) {
        registry.add("mithila.routing.osrm-url", ApiIntegrationTestSupport::startRouteServer);
    }

    private static synchronized String startRouteServer() {
        if (routeServer != null) return "http://127.0.0.1:" + routeServer.getAddress().getPort();
        try {
            routeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            routeServer.createContext("/route/v1/driving/", exchange -> {
                byte[] response = """
                        {"code":"Ok","routes":[{"distance":1600,"duration":180,"geometry":{"type":"LineString","coordinates":[[86.071,26.3543],[86.0731,26.367]]}}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(response);
                }
            });
            routeServer.start();
            return "http://127.0.0.1:" + routeServer.getAddress().getPort();
        } catch (IOException error) {
            throw new IllegalStateException("Could not start the local test routing stub.", error);
        }
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected RideService rideService;

    protected UserAccount createRider(String label) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return rideService.signup(label, label.toLowerCase() + "-" + suffix + "@example.com",
                randomPhone("6"), TEST_PASSWORD, "RIDER", null, null);
    }

    protected UserAccount createDriver(String label) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return rideService.signup(label, label.toLowerCase() + "-" + suffix + "@example.com",
                randomPhone("7"), TEST_PASSWORD, "DRIVER", "Auto", "BR-01-" + suffix.substring(0, 6));
    }

    protected MockHttpSession login(UserAccount user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/login").with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"identifier":"%s","password":"%s","role":"%s"}
                                """.formatted(user.getEmail(), TEST_PASSWORD, user.getRole())))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    protected static String randomPhone(String firstDigit) {
        return firstDigit + ThreadLocalRandom.current().nextInt(100_000_000, 1_000_000_000);
    }
}
