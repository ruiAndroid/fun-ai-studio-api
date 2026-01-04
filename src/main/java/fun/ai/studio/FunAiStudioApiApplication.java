package fun.ai.studio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FunAiStudioApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(FunAiStudioApiApplication.class, args);
    }
}
