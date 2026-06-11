package dev.mewcode.agent.prompt;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EnvironmentInfoTest {
    @Test
    public void rendersKnownFields() {
        EnvironmentInfo info = new EnvironmentInfo("D:/work", "Windows", "2026-06-10", "", "0.1.0", "test-model");
        String rendered = info.render();
        assertTrue(rendered.contains("Working Directory: D:/work"));
        assertTrue(rendered.contains("Platform: Windows"));
        assertTrue(rendered.contains("Date: 2026-06-10"));
        assertTrue(rendered.contains("App Version: 0.1.0"));
        assertTrue(rendered.contains("Model: test-model"));
        assertFalse(rendered.contains("Git Status:"));
    }

    @Test
    public void gatherDoesNotThrowOutsideGit() {
        String rendered = EnvironmentInfo.gather("0.1.0", "test-model").render();
        assertTrue(rendered.contains("Environment Information:"));
        assertTrue(rendered.contains("Working Directory:"));
        assertTrue(rendered.contains("Platform:"));
        assertTrue(rendered.contains("Date:"));
    }
}
