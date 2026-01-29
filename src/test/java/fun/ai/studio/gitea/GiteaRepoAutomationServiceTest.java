package fun.ai.studio.gitea;

import fun.ai.studio.config.GiteaProperties;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class GiteaRepoAutomationServiceTest {

    @Test
    void ensureRepoAndGrantRunner_shouldInitGitignore() {
        GiteaProperties props = new GiteaProperties();
        props.setOwner("funai");
        props.setRepoNameTemplate("u{userId}-app{appId}");
        props.setDefaultBranch("main");

        GiteaClient client = mock(GiteaClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.ensureOrgRepo(anyString(), anyString(), anyBoolean(), anyBoolean(), anyString())).thenReturn(true);
        when(client.ensureFile(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        // 让授权流程走 team 分支并成功，避免测试输出 warn（与本测试目的无关）
        when(client.findTeamIdByName(anyString(), anyString())).thenReturn(1L);
        when(client.grantTeamRepo(eq(1L), anyString(), anyString())).thenReturn(true);

        GiteaRepoAutomationService svc = new GiteaRepoAutomationService(props, client, null);
        svc.ensureRepoAndGrantRunner(1L, 2L);

        verify(client).ensureFile(
                eq("funai"),
                eq("u1-app2"),
                eq("main"),
                eq(".gitignore"),
                argThat(s -> s != null && s.contains("node_modules")),
                eq("init .gitignore")
        );
    }
}


