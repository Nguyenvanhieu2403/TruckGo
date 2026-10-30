package com.truckgo.admin;

import com.truckgo.user.ContactNormalizer;
import com.truckgo.user.PasswordPolicy;
import com.truckgo.user.User;
import com.truckgo.user.UserRepository;
import com.truckgo.user.UserRole;
import com.truckgo.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-shot creation of the first ADMIN. Runs only when the application is started with {@code --bootstrap-admin}
 * (the web server is not started in that mode, see TruckGoApplication). Credentials come from environment variables;
 * there is no default password. Idempotent: an existing admin with the same e-mail is left untouched, and a
 * non-admin account is never promoted silently.
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {
    public static final String OPTION = "bootstrap-admin";
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final Environment env;

    public AdminBootstrapRunner(UserRepository users, PasswordEncoder passwordEncoder, TransactionTemplate tx, Environment env) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tx = tx;
        this.env = env;
    }

    @Override public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) return;
        String email = ContactNormalizer.email(required("ADMIN_BOOTSTRAP_EMAIL"));
        String password = required("ADMIN_BOOTSTRAP_PASSWORD");
        String configuredName = env.getProperty("ADMIN_BOOTSTRAP_FULL_NAME", "");
        String fullName = configuredName.isBlank() ? "TruckGo Admin" : configuredName.trim();
        if (!PasswordPolicy.isValid(password) || password.length() < 12)
            throw new IllegalStateException("ADMIN_BOOTSTRAP_PASSWORD must be at least 12 characters and contain letters and digits");
        tx.executeWithoutResult(status -> {
            var existing = users.findByEmail(email);
            if (existing.isPresent()) {
                if (existing.get().getRole() != UserRole.ADMIN) throw new IllegalStateException("A non-admin account already uses ADMIN_BOOTSTRAP_EMAIL; refusing to promote it");
                log.info("Admin bootstrap skipped: admin already exists userId={}", existing.get().getId());
                return;
            }
            User admin = users.save(new User(fullName, email, null, passwordEncoder.encode(password), UserRole.ADMIN, UserStatus.ACTIVE));
            log.info("Admin bootstrap created admin userId={}", admin.getId());
        });
    }

    private String required(String name) {
        String value = env.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for --" + OPTION);
        return value;
    }
}
