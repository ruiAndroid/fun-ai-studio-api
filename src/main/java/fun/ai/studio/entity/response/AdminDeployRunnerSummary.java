package fun.ai.studio.entity.response;

import lombok.Data;

@Data
public class AdminDeployRunnerSummary {
    private String runnerId;
    private Long lastSeenAtMs;
    private String health; // HEALTHY / STALE
}


