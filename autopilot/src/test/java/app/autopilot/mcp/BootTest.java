package app.autopilot.mcp;

import app.autopilot.testkit.AppTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class BootTest extends AppTestBase {
    @Autowired ApplicationContext ctx;

    @Test
    @DisplayName("the server boots with the real security chains, MCP endpoint and migrations")
    void boots() {
        assertThat(ctx.getBeansOfType(ReadTools.class)).hasSize(1);
        assertThat(ctx.getBeansOfType(ChangeTools.class)).hasSize(1);
    }
}
