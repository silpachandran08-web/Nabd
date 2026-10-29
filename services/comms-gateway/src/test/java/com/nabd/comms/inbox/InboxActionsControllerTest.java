package com.nabd.comms.inbox;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** COM-032: internal API shape, service-token gate, status codes and Problem+JSON codes (the service is covered by ReplyServiceTest). */
class InboxActionsControllerTest {

    final ReplyService service = Mockito.mock(ReplyService.class);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new InboxActionsController(service, "test-token")).setControllerAdvice(new InboxErrors()).build();
    final UUID tenant = UUID.randomUUID(), staff = UUID.randomUUID(), thread = UUID.randomUUID();
    final String base = "/internal/v1/inbox/threads/";

    @Test
    void needsTheServiceTokenTheTenantAndTheStaffHeaders() throws Exception {
        String body = "{\"text\":\"hi\"}";
        mvc.perform(post(base + thread + "/replies").header("X-Tenant-Id", tenant).header("X-Staff-Id", staff).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(base + thread + "/replies").header("X-Internal-Token", "test-token").header("X-Tenant-Id", tenant).contentType("application/json").content(body))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/internal/v1/inbox/canned-replies").header("X-Tenant-Id", tenant)).andExpect(status().isUnauthorized());
    }

    @Test
    void replyIs201AndPassesTheKeyAndTheOverrideFlag() throws Exception {
        UUID msg = UUID.randomUUID();
        when(service.reply(eq(tenant), eq(thread), eq(staff), eq("hi"), eq(true), eq("k1"))).thenReturn(new ReplyService.Sent(msg, "queued"));
        mvc.perform(post(base + thread + "/replies").header("X-Internal-Token", "test-token").header("X-Tenant-Id", tenant).header("X-Staff-Id", staff)
                        .header("Idempotency-Key", "k1").contentType("application/json").content("{\"text\":\"hi\",\"overrideClinical\":true}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.messageId").value(msg.toString())).andExpect(jsonPath("$.status").value("queued"));
    }

    @Test
    void windowClosedIs409AndClinicalLookingTextIs422WithTheirCodes() throws Exception {
        when(service.reply(any(), any(), any(), any(), Mockito.anyBoolean(), any())).thenThrow(new InboxException(HttpStatus.CONFLICT, "SESSION_WINDOW_CLOSED"));
        mvc.perform(post(base + thread + "/replies").header("X-Internal-Token", "test-token").header("X-Tenant-Id", tenant).header("X-Staff-Id", staff)
                        .contentType("application/json").content("{\"text\":\"hi\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_WINDOW_CLOSED"));
        Mockito.doThrow(new InboxException(HttpStatus.UNPROCESSABLE_ENTITY, "TEXT_LOOKS_CLINICAL")).when(service).reply(any(), any(), any(), any(), Mockito.anyBoolean(), any());
        mvc.perform(post(base + thread + "/replies").header("X-Internal-Token", "test-token").header("X-Tenant-Id", tenant).header("X-Staff-Id", staff)
                        .contentType("application/json").content("{\"text\":\"hi\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TEXT_LOOKS_CLINICAL"));
    }

    @Test
    void assignModeAndNoteRouteToTheService() throws Exception {
        UUID priya = UUID.randomUUID();
        when(service.assign(tenant, thread, staff, priya)).thenReturn(new ReplyService.ThreadState(thread, "human", priya, false, false));
        mvc.perform(post(base + thread + "/assign").header("X-Internal-Token", "test-token").header("X-Tenant-Id", tenant).header("X-Staff-Id", staff)
                .contentType("application/json").content("{\"staffId\":\"" + priya + "\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.assignedTo").value(priya.toString()));
        mvc.perform(post(base + thread + "/mode").header("X-Internal-Token", "test-token").header("X-Tenant-Id", tenant).header("X-Staff-Id", staff)
                .contentType("application/json").content("{\"mode\":\"bot\"}")).andExpect(status().isOk());
        verify(service).setMode(tenant, thread, staff, "bot");
        mvc.perform(post(base + thread + "/notes").header("X-Internal-Token", "test-token").header("X-Tenant-Id", tenant).header("X-Staff-Id", staff)
                .contentType("application/json").content("{\"outcome\":\"reached\"}")).andExpect(status().isCreated());
    }
}
