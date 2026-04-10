package fun.ai.studio.controller.common;

import fun.ai.studio.entity.FunAiApp;
import fun.ai.studio.enums.FunAiAppStatus;
import fun.ai.studio.service.FunAiAppService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicAppSlugControllerTest {

    @Test
    void route_shouldRedirectReadyAppToRuntimePath() throws Exception {
        FunAiAppService appService = mock(FunAiAppService.class);
        FunAiApp app = new FunAiApp();
        app.setId(123L);
        app.setAppStatus(FunAiAppStatus.READY.code());
        when(appService.getAppBySlug("ai-writer")).thenReturn(app);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PublicAppSlugController(appService)).build();

        mockMvc.perform(get("/ai-writer"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/runtime/123"));
    }

    @Test
    void route_shouldReturnPendingHtmlForNonReadyApp() throws Exception {
        FunAiAppService appService = mock(FunAiAppService.class);
        FunAiApp app = new FunAiApp();
        app.setId(456L);
        app.setAppStatus(FunAiAppStatus.DEPLOYING.code());
        when(appService.getAppBySlug("demo")).thenReturn(app);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PublicAppSlugController(appService)).build();

        mockMvc.perform(get("/demo"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<code>demo</code>")));
    }

    @Test
    void route_shouldReturn404ForReservedSlug() throws Exception {
        FunAiAppService appService = mock(FunAiAppService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PublicAppSlugController(appService)).build();

        mockMvc.perform(get("/runtime"))
                .andExpect(status().isNotFound());
    }
}
