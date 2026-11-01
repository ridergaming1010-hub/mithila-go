package mithila.mith.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AadhaarHmacKey {
    private static final Logger logger = LoggerFactory.getLogger(AadhaarHmacKey.class);
    private static final int MINIMUM_KEY_BYTES = 32;

    private final String value;

    public AadhaarHmacKey(
            @Value("${mithila.aadhaar.hmac-key:}") String configuredKey,
            @Value("${mithila.aadhaar.hmac-key-file:./data/aadhaar-hmac.key}") String keyFile) {
        this.value = resolve(configuredKey, Path.of(keyFile));
    }

    public String value() {
        return value;
    }

    private String resolve(String configuredKey, Path keyFile) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            return validate(configuredKey.trim());
        }

        try {
            if (Files.exists(keyFile)) {
                return validate(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
            }

            Path parent = keyFile.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            String generatedKey = Base64.getEncoder().withoutPadding()
                    .encodeToString(randomBytes());
            try {
                Files.writeString(keyFile, generatedKey, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                restrictPermissions(keyFile);
                logger.info("Created a persistent local Aadhaar sign-in key at {}. Keep this file with the database.",
                        keyFile.toAbsolutePath());
                return generatedKey;
            } catch (FileAlreadyExistsException exception) {
                return validate(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not load or create the local Aadhaar sign-in key at " + keyFile.toAbsolutePath(),
                    exception);
        }
    }

    private String validate(String key) {
        if (key.getBytes(StandardCharsets.UTF_8).length < MINIMUM_KEY_BYTES) {
            throw new IllegalStateException(
                    "MITHILA_AADHAAR_HMAC_KEY must contain at least 32 characters, or remove the invalid "
                            + "local Aadhaar key file so a new local key can be generated.");
        }
        return key;
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[MINIMUM_KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private void restrictPermissions(Path keyFile) throws IOException {
        try {
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows does not support POSIX file permissions.
        }
    }
}
