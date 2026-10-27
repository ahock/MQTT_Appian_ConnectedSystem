package de.hock.mqtt;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Packaging smoke test: the two resources Appian needs must be on the classpath.
 *
 * <p>If {@code appian-plugin.xml} is missing, Appian ignores the plug-in entirely; if
 * {@code resources_en_US.properties} is missing, the templates and their fields render without
 * names or labels. Both must also land as bare root entries in the assembled jar, which the
 * packaging-invariant checks in {@code .agents/tasks/minimal-smoke-build.md} verify against the
 * built artifact.
 */
class ProjectStructureTest {

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
