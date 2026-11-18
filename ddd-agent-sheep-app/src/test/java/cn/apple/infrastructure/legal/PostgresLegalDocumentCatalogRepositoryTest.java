package cn.apple.infrastructure.legal;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PostgresLegalDocumentCatalogRepositoryTest {
    @Test
    public void bindsRegistrationInstantAsJdbcTimestamp() throws Exception {
        List<Object> boundValues = new ArrayList<>();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("setObject") || method.getName().equals("setTimestamp")) {
                        if (arguments[1] instanceof Instant) throw new AssertionError("Instant must not reach JDBC setObject");
                        boundValues.add(arguments[1]);
                    }
                    return method.getName().equals("executeUpdate") ? 1 : null;
                });
        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> method.getName().equals("prepareStatement") ? statement : null);
        Instant registeredAt = Instant.parse("2026-11-18T05:00:00.123456Z");
        Method update = PostgresLegalDocumentCatalogRepository.class.getDeclaredMethod(
                "update", Connection.class, String.class, Object[].class);
        update.setAccessible(true);

        Object affected = update.invoke(new PostgresLegalDocumentCatalogRepository(new JdbcTemplate()),
                connection, "insert test", new Object[]{"document-1", registeredAt});

        assertEquals(1, affected);
        assertEquals(List.of("document-1", Timestamp.from(registeredAt)), boundValues);
    }
}
