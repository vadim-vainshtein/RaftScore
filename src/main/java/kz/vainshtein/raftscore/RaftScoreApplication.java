package kz.vainshtein.raftscore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RaftScoreApplication {

    static void main(String[] args) {
        SpringApplication.run(RaftScoreApplication.class, args);
    }
}
