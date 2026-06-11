package dev.mewcode.agent.prompt;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class ReminderTest {
    @Test
    public void wrapsReminderWithSystemReminderTag() {
        String reminder = Reminder.systemReminder("hello");
        assertTrue(reminder.contains("<system-reminder>"));
        assertTrue(reminder.contains("hello"));
        assertTrue(reminder.contains("</system-reminder>"));
    }

    @Test
    public void providesFullAndConcisePlanReminders() {
        String full = Reminder.plan(true);
        String concise = Reminder.plan(false);
        assertTrue(full.length() > concise.length());
        assertTrue(full.contains("PLAN MODE"));
        assertTrue(concise.contains("PLAN MODE"));
    }
}
