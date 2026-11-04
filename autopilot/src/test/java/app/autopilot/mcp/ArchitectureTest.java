package app.autopilot.mcp;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** Claude's only route to the server is the set of MCP tools, and that set must not include approval or control. */
class ArchitectureTest {

    /** The PRD's catalog: Tier 0 reads, the change tools, and propose_rollback. */
    static final Set<String> CATALOG = new TreeSet<>(Set.of(
            "get_agent_context", "get_account_health", "get_conversion_health", "get_performance", "get_search_terms", "get_keywords", "get_ads",
            "get_change_history", "get_recommendations", "run_report", "list_proposals", "get_proposal", "record_run_summary",
            "add_negative_keywords", "pause_keyword", "adjust_keyword_bid", "decrease_budget", "emergency_pause_campaign",
            "pause_ad", "add_keywords", "create_ad", "create_campaign", "add_ad_group", "increase_budget", "change_bidding_strategy",
            "enable_entity", "apply_recommendation", "propose_rollback", "get_change_outcomes", "record_outcome_review"));

    private static JavaClasses classes;

    @BeforeAll
    static void load() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("app.autopilot");
    }

    static Set<String> toolNames() {
        Set<String> names = new TreeSet<>();
        List<Class<?>> toolClasses = List.of(ReadTools.class, ChangeTools.class);
        for (Class<?> c : toolClasses) {
            for (Method m : c.getDeclaredMethods()) {
                McpTool t = m.getAnnotation(McpTool.class);
                if (t != null) names.add(t.name());
            }
        }
        return names;
    }

    @Test
    @DisplayName("[AC-11] the registered MCP tools are exactly the PRD catalog")
    void toolsEqualCatalog() {
        assertThat(toolNames()).containsExactlyElementsOf(CATALOG);
    }

    @Test
    @DisplayName("[AC-11] no MCP tool is named like an approval, limit, autonomy or stop-mechanism control")
    void noControlToolNames() {
        List<String> forbidden = List.of("approve", "reject", "limit", "autonomy", "kill", "breaker", "resume", "clear", "secret",
                "token", "link", "admin", "config", "mutate", "execute", "raw", "fetch", "send", "email", "url");
        List<String> offenders = new ArrayList<>();
        for (String name : toolNames()) {
            for (String f : forbidden) if (name.contains(f)) offenders.add(name + " contains '" + f + "'");
        }
        assertThat(offenders).as("tool names that sound like control or generic access").isEmpty();
    }

    @Test
    @DisplayName("[AC-11] the mcp package does not depend on the approval or security packages")
    void mcpDoesNotReachApproval() {
        noClasses().that().resideInAPackage("app.autopilot.mcp..")
                .should().dependOnClassesThat().resideInAnyPackage("app.autopilot.approval..", "app.autopilot.security..")
                .because("Claude must have no code path that can approve a proposal or touch limits and stop mechanisms")
                .check(classes);
    }

    @Test
    @DisplayName("[AC-11] nothing the MCP tools use can approve or change limits: the approval classes are reachable only from approval")
    void approvalClassesAreIsolated() {
        noClasses().that().resideOutsideOfPackages("app.autopilot.approval..", "app.autopilot.security..")
                .and().haveSimpleNameNotEndingWith("Test")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.autopilot.approval.ApprovalService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("app.autopilot.approval.AdminService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("app.autopilot.approval.ApprovalLinks")
                .check(classes);
    }

    @Test
    @DisplayName("[AC-11] the MCP tools never write limits, goals, control state or proposal decisions")
    void mcpNeverWritesControlState() {
        noClasses().that().resideInAPackage("app.autopilot.mcp..")
                .should().dependOnClassesThat().haveFullyQualifiedName("app.autopilot.store.ConfigStore")
                .because("limits, goals, the kill switch and the breaker live in ConfigStore and only server configuration "
                        + "and the owner pages change them")
                .check(classes);
    }
}
