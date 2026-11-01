package mithila.mith.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AadhaarHmacKeyTests {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatedKeyIsKeptAcrossApplicationRestarts() {
        Path keyFile = temporaryDirectory.resolve("aadhaar-hmac.key");

        String firstKey = new AadhaarHmacKey("", keyFile.toString()).value();
        String restartedKey = new AadhaarHmacKey("", keyFile.toString()).value();

        assertTrue(Files.exists(keyFile));
        assertTrue(firstKey.length() >= 32);
        assertEquals(firstKey, restartedKey);
    }

    @Test
    void configuredKeyOverridesLocalFile() {
        Path keyFile = temporaryDirectory.resolve("aadhaar-hmac.key");
        String configuredKey = "deployment-secret-with-at-least-32-characters";

        AadhaarHmacKey key = new AadhaarHmacKey(configuredKey, keyFile.toString());

        assertEquals(configuredKey, key.value());
        assertFalse(Files.exists(keyFile));
    }

    @Test
    void rejectsAnInvalidConfiguredKey() {
        assertThrows(IllegalStateException.class,
                () -> new AadhaarHmacKey("short", temporaryDirectory.resolve("aadhaar-hmac.key").toString()));
    }
}
