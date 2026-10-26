package com.nabd.comms.inbox;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** COM-030.3: internal API shape, service-token gate and Problem+JSON codes (the service itself is covered by InboxServiceTest). */
class InboxInternalControllerTest {

    final InboxService service = Mockito.mock(InboxService.class);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new InboxInternalController(service, "test-token")).setControllerAdvice(new InboxErrors()).build();
    final UUID tenant = UUID.randomUUID();

    @Test
    void requiresTheServiceTokenAndTheTenantHeader() throws Exception {
        mvc.perform(get("/internal/v1/inbox/threads").header("X-Tenant-Id", tenant)).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/inbox/threads").header("X-Tenant-Id", tenant).header("X-Internal-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/inbox/threads").header("X-Internal-Token", "test-token")).andExpect(status().is4xxClientError());
        var closed = MockMvcBuilders.standaloneSetup(new InboxInternalController(service, "")).setControllerAdvice(new InboxErrors()).build();
        closed.perform(get("/internal/v1/inbox/threads").header("X-Tenant-Id", tenant).header("X-Internal-Token", "")).andExpect(status().isUnauthorized());
    }

    @Test
    void listsThreadsWithFilterAndCursor() throws Exception {
        when(service.threads(tenant, "needs_reply", "c1", 20)).thenReturn(new InboxService.ThreadPage(List.of(), "c2"));
        mvc.perform(get("/internal/v1/inbox/threads?filter=needs_reply&cursor=c1&limit=20").header("X-Tenant-Id", tenant).header("X-Internal-Token", "test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextCursor").value("c2"));
    }

    @Test
    void mapsServiceErrorsToProblemJsonWithACode() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.messages(any(), any(), any(), Mockito.anyInt())).thenThrow(new InboxException(HttpStatus.NOT_FOUND, "THREAD_NOT_FOUND"));
        mvc.perform(get("/internal/v1/inbox/threads/" + id + "/messages").header("X-Tenant-Id", tenant).header("X-Internal-Token", "test-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("THREAD_NOT_FOUND"));
    }

    @Test
    void markReadIs204AndNeedsAMessageId() throws Exception {
        UUID id = UUID.randomUUID(), msg = UUID.randomUUID();
        mvc.perform(post("/internal/v1/inbox/threads/" + id + "/read").header("X-Tenant-Id", tenant).header("X-Internal-Token", "test-token")
                .contentType("application/json").content("{\"upToMessageId\":\"" + msg + "\"}")).andExpect(status().isNoContent());
        verify(service).markRead(tenant, id, msg);
        mvc.perform(post("/internal/v1/inbox/threads/" + id + "/read").header("X-Tenant-Id", tenant).header("X-Internal-Token", "test-token")
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UP_TO_MESSAGE_REQUIRED"));
    }
}
