package kz.vainshtein.raftscore;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Environment-specific settings required to operate one RaftScore installation. */
@ConfigurationProperties("raftscore")
public record RaftScoreProperties(
        @DefaultValue InitialAdministrator initialAdministrator) {

    /** Credentials used only when the first full-access user is created. */
    public record InitialAdministrator(
            @DefaultValue("admin") String username,
            String password) {
    }
}
