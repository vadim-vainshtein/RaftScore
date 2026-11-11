package kz.vainshtein.raftscore.api;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DatabaseUnavailableHttpTest {
    @Test
    void failedCommitReturnsSafeServiceUnavailableAndRecoverySucceeds() throws Exception {
        var controller = new SaveController();
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post("/save")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("database.unavailable"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))));
        controller.outage = false;
        mvc.perform(post("/save")).andExpect(status().isNoContent());
    }

    @RestController
    static class SaveController {
        boolean outage = true;
        @PostMapping("/save")
        org.springframework.http.ResponseEntity<Void> save() {
            if (outage) {
                throw new TransactionSystemException("password", new SQLException("secret SQL", "08006"));
            }
            return org.springframework.http.ResponseEntity.noContent().build();
        }
    }
}
