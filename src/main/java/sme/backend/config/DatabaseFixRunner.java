package sme.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseFixRunner implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            log.info("Checking and fixing database constraints...");
            // Drop outdated check constraints that prevent inserting new enum values
            jdbcTemplate.execute("ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check");
            jdbcTemplate.execute("ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_payment_status_check");
            jdbcTemplate.execute("ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_type_check");
            
            // Also drop constraints on the Envers audit table
            jdbcTemplate.execute("ALTER TABLE orders_audit DROP CONSTRAINT IF EXISTS orders_audit_status_check");
            jdbcTemplate.execute("ALTER TABLE orders_audit DROP CONSTRAINT IF EXISTS orders_audit_payment_status_check");
            jdbcTemplate.execute("ALTER TABLE orders_audit DROP CONSTRAINT IF EXISTS orders_audit_type_check");
            
            // Also drop constraints on order_status_history just in case
            jdbcTemplate.execute("ALTER TABLE order_status_history DROP CONSTRAINT IF EXISTS order_status_history_new_status_check");
            jdbcTemplate.execute("ALTER TABLE order_status_history DROP CONSTRAINT IF EXISTS order_status_history_old_status_check");
            
            log.info("Successfully cleaned up outdated enum constraints in database.");
        } catch (Exception e) {
            log.warn("Could not execute constraint cleanup: {}", e.getMessage());
        }
    }
}
