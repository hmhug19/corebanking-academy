package vn.com.coreacademy.identity.entity;

import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.spi.MetadataImplementor;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Table;
import org.junit.jupiter.api.Test;
import vn.com.coreacademy.audit.entity.AuditLog;
import vn.com.coreacademy.organization.entity.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class IdentityMappingTest {
    @Test
    void hibernateMappingsMatchStandaloneSql() throws Exception {
        String sql = Files.readString(Path.of("docs/sql/learning_hub_identity.sql"));
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", PostgreSQLDialect.class.getName())
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            for (Class<?> entity : List.of(UserAccount.class, UserCredential.class, Role.class,
                    Permission.class, RolePermission.class, RoleAssignment.class, Organization.class,
                    Team.class, Position.class, UserOrganization.class, UserTeam.class, UserPosition.class, AuditLog.class))
                sources.addAnnotatedClass(entity);
            MetadataImplementor metadata = (MetadataImplementor) sources.buildMetadata();
            metadata.validate();
            Map<String, Table> mapped = new HashMap<>();
            metadata.collectTableMappings().forEach(table -> mapped.put(table.getName(), table));
            var tables = Pattern.compile("CREATE TABLE learning_hub\\.(\\w+) \\((.*?)\\n\\);", Pattern.DOTALL).matcher(sql);
            int count = 0;
            while (tables.find()) {
                count++;
                Table table = mapped.remove(tables.group(1));
                assertNotNull(table, "Unmapped SQL table: " + tables.group(1));
                assertEquals("learning_hub", table.getSchema());
                String body = tables.group(2);
                Map<String, String> expectedColumns = new HashMap<>();
                var columns = Pattern.compile("(?m)^    (\\w+) (varchar\\(\\d+\\)|bigint|integer|boolean|timestamptz|jsonb|text)").matcher(body);
                while (columns.find()) expectedColumns.put(columns.group(1), columns.group(2));
                assertEquals(expectedColumns.keySet(), table.getColumns().stream().map(Column::getName).collect(Collectors.toSet()), table.getName());
                for (Column column : table.getColumns()) {
                    String expected = expectedColumns.get(column.getName());
                    String actual = column.getSqlType(metadata);
                    if (expected.equals("timestamptz")) assertTrue(actual.startsWith("timestamp") && actual.endsWith("with time zone"), actual);
                    else assertEquals(expected, actual, table.getName() + "." + column.getName());
                }
                Set<String> expectedKeys = new HashSet<>();
                var compound = Pattern.compile("PRIMARY KEY \\(([^)]+)\\)").matcher(body);
                if (compound.find()) Arrays.stream(compound.group(1).split(",")).map(String::trim).forEach(expectedKeys::add);
                else {
                    var single = Pattern.compile("(?m)^    (\\w+) [^\\n]*PRIMARY KEY").matcher(body);
                    assertTrue(single.find());
                    expectedKeys.add(single.group(1));
                }
                assertEquals(expectedKeys, table.getPrimaryKey().getColumns().stream().map(Column::getName).collect(Collectors.toSet()), table.getName());
            }
            assertEquals(13, count);
            assertTrue(mapped.isEmpty(), "Unexpected mapped tables: " + mapped.keySet());
            try (var factory = metadata.buildSessionFactory()) {
                assertEquals(13, factory.getMetamodel().getEntities().size());
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
