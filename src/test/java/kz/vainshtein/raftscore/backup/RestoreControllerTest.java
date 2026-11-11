package kz.vainshtein.raftscore.backup;

import java.io.IOException;
import java.util.UUID;
import kz.vainshtein.raftscore.auth.configurations.RestoreGateFilter;
import kz.vainshtein.raftscore.auth.configurations.SessionEpoch;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RestoreControllerTest {
    private final RestoreService service = mock(RestoreService.class);
    private final AuthenticationManager authentication = mock(AuthenticationManager.class);
    private final SessionEpoch epoch = mock(SessionEpoch.class);
    private final UUID id = UUID.randomUUID();
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new RestoreController(service, authentication, epoch))
            .addFilters(new RestoreGateFilter()).build();

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(boolean confirmed) {
        return post("/api/backups/restore").principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + id + "\",\"confirmed\":" + confirmed + ",\"password\":\"secret\"}");
    }

    @Test void confirmationAndPasswordAreRequired() throws Exception {
        mvc.perform(request(false)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("backup.confirmation_required"));
        when(authentication.authenticate(any())).thenThrow(new BadCredentialsException("sensitive"));
        mvc.perform(request(true)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("backup.reauthentication_failed"));
        verifyNoInteractions(service, epoch);
    }

    @Test void successInvalidatesSessionsAndFailureDoesNot() throws Exception {
        when(authentication.authenticate(any())).thenReturn(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_FULL_ACCESS"))));
        mvc.perform(request(true)).andExpect(status().isNoContent());
        verify(service).restore(id);
        verify(epoch).invalidateAll();
        reset(epoch);
        doThrow(new IOException("sensitive details")).when(service).restore(id);
        mvc.perform(request(true)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("backup.restore_failed"));
        verify(epoch, never()).invalidateAll();
    }

    @Test void requestStringRedactsPassword() {
        org.assertj.core.api.Assertions.assertThat(new RestoreController.RestoreRequest(id, true, "secret").toString()).doesNotContain("secret");
    }
}
