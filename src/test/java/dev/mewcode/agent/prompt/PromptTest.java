package dev.mewcode.agent.prompt;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PromptTest {
    @Test
    public void buildsStableSystemPromptInPriorityOrder() {
        String prompt = Prompt.buildSystemPrompt();
        assertTrue(prompt.indexOf("You are MewCode") < prompt.indexOf("Prefer dedicated tools"));
        assertTrue(prompt.contains("\n\n"));
    }

    @Test
    public void skipsEmptyOptionalModules() {
        String prompt = Prompt.buildSystemPrompt();
        assertFalse(prompt.contains("\n\n\n"));
    }

    @Test
    public void remainsStableAcrossCalls() {
        assertEquals(Prompt.buildSystemPrompt(), Prompt.buildSystemPrompt());
    }

    @Test
    public void stablePromptDoesNotContainEnvironmentFields() {
        String prompt = Prompt.buildSystemPrompt();
        assertFalse(prompt.contains("Working Directory:"));
        assertFalse(prompt.contains("Platform:"));
        assertFalse(prompt.contains("Date:"));
        assertFalse(prompt.contains("Git Status:"));
    }

    @Test
    public void insertsAdditionalModuleByPriority() {
        List<PromptModule> modules = new ArrayList<PromptModule>(Prompt.fixedModules());
        modules.add(new PromptModule("extra", 55, "EXTRA"));
        String prompt = Prompt.assembleSystem(modules);
        assertTrue(prompt.indexOf("Prefer dedicated tools") < prompt.indexOf("EXTRA"));
        assertTrue(prompt.indexOf("EXTRA") < prompt.indexOf("Answer in concise Chinese"));
    }

    @Test
    public void insertsInstructionsAndMemoryModulesWhenProvided() {
        String prompt = Prompt.buildSystemPrompt("PROJECT_RULE", "MEMORY_INDEX");
        assertTrue(prompt.contains("PROJECT_RULE"));
        assertTrue(prompt.contains("MEMORY_INDEX"));
        assertTrue(prompt.indexOf("PROJECT_RULE") < prompt.indexOf("MEMORY_INDEX"));
    }
}
