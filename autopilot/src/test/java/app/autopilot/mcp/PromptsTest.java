package app.autopilot.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The versioned prompts follow the run contract and ask Claude to use only tools that exist. */
class PromptsTest {

    private static final List<String> LOOPS = List.of("triage", "daily", "weekly", "chat");
    private static final Pattern TOOLISH = Pattern.compile("`((?:get|add|pause|adjust|decrease|increase|create|change|enable|apply|propose|emergency|record|list|run)_[a-z_]+)`");

    private static String prompt(String loop) throws IOException {
        return Files.readString(Path.of("prompts/" + loop + "-v1.md"));
    }

    @Test
    @DisplayName("[AC-39] every loop has a versioned prompt whose header names its own version")
    void versioned() throws IOException {
        assertThat(Files.readString(Path.of("prompts/VERSION")).strip()).isEqualTo("v1");
        for (String loop : LOOPS) {
            String p = prompt(loop);
            assertThat(p).startsWith("Prompt version: " + loop + "-v1");
            assertThat(p).as("%s passes its version to get_agent_context", loop).contains("prompt_version=" + loop + "-v1");
            assertThat(p).contains("Loop: " + loop.toUpperCase());
        }
    }

    @Test
    @DisplayName("[AC-39] each prompt starts with get_agent_context and ends the run with record_run_summary")
    void followsTheRunContract() throws IOException {
        for (String loop : LOOPS) {
            String p = prompt(loop);
            String contract = p.substring(p.indexOf("## Run contract"), p.indexOf("## Rules"));
            List<String> steps = new ArrayList<>(List.of(contract.split("(?m)^\\d+\\. ")));
            steps.remove(0); // the heading before step 1
            assertThat(steps.get(0)).as("%s step 1", loop).contains("`get_agent_context`").contains("loop=" + loop.toUpperCase());
            assertThat(steps.get(steps.size() - 1)).as("%s last step", loop).contains("`record_run_summary`");
        }
    }

    @Test
    @DisplayName("[AC-39] prompts mention only catalog tools")
    void onlyCatalogTools() throws IOException {
        for (String loop : LOOPS) {
            Matcher m = TOOLISH.matcher(prompt(loop));
            while (m.find()) {
                assertThat(ArchitectureTest.CATALOG).as("tool `%s` in the %s prompt", m.group(1), loop).contains(m.group(1));
            }
        }
    }

    @Test
    @DisplayName("[AC-39] prompts carry the untrusted-data and no-approval rules; only the daily loop may expect direct changes")
    void safetyRules() throws IOException {
        for (String loop : LOOPS) {
            String p = prompt(loop);
            assertThat(p).as(loop).contains("untrusted").containsAnyOf("cannot approve", "You cannot approve");
            assertThat(p).as(loop).containsPattern("idempotency_key");
        }
        assertThat(prompt("weekly")).contains("Everything you file is a proposal");
        assertThat(prompt("chat")).contains("Nothing you do here is applied directly");
        assertThat(prompt("triage")).contains("emergency_pause_campaign").contains("Nothing else is applied by this loop");
        assertThat(prompt("daily")).contains("Tier 1").contains("Never revert");
    }
}
