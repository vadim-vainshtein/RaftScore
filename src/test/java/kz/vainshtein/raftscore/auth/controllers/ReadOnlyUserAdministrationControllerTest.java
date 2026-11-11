package kz.vainshtein.raftscore.auth.controllers;

import kz.vainshtein.raftscore.auth.services.ReadOnlyUserAdministrationService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReadOnlyUserAdministrationControllerTest {
    @Test
    void translatesConcurrentUserUpdatesToConflict() throws Exception {
        var service = mock(ReadOnlyUserAdministrationService.class);
        doThrow(new OptimisticLockingFailureException("stale")).when(service).block(1L);
        doThrow(new OptimisticLockingFailureException("stale")).when(service).resetPassword(eq(1L), any());
        var mvc = MockMvcBuilders.standaloneSetup(new ReadOnlyUserAdministrationController(service)).build();

        mvc.perform(patch("/api/users/1/block")).andExpect(status().isConflict());
        mvc.perform(put("/api/users/1/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"password\"}"))
                .andExpect(status().isConflict());
    }
}
