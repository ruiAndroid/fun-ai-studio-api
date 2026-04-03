package fun.ai.studio.entity.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailStatusResponse {

    @Schema(description = "脱敏邮箱")
    private String emailMasked;

    @Schema(description = "是否已绑定")
    private Boolean bound;
}
