package de.hock.mqtt;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Scaffolding smoke test (Task 1).
 *
 * <p>Confirms the base package resolves and the plug-in descriptor resources are present and
 * packaged on the classpath, so the Maven module builds, tests, and packages end-to-end. These
 * assertions are replaced/augmented by real unit and property tests in later tasks.
 */
class ProjectStructureTest {

    @Test
    void basePackageIsPresent() {
        Package basePackage = ProjectStructureTest.class.getPackage();
        assertNotNull(basePackage, "Base package should resolve");
        assertTrue(
            basePackage.getName().startsWith("de.hock.mqtt"),
            "Tests live under the de.hock.mqtt base package");
    }

    @Test
    void pluginDescriptorIsOnClasspath() throws Exception {
        try (InputStream descriptor =
            getClass().getClassLoader().getResourceAsStream("appian-plugin.xml")) {
            assertNotNull(descriptor, "appian-plugin.xml must be packaged on the classpath");
        }
    }

    @Test
    void defaultResourceBundleIsOnClasspath() throws Exception {
        try (InputStream bundle =
            getClass().getClassLoader().getResourceAsStream("resources_en_US.properties")) {
            assertNotNull(bundle, "resources_en_US.properties must be packaged on the classpath");
        }
    }
}
