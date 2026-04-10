package fun.ai.studio.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSlugPolicyTest {

    @Test
    void normalize_shouldTrimAndLowercase() {
        assertEquals("ai-writer", AppSlugPolicy.normalize("  AI-Writer  "));
        assertNull(AppSlugPolicy.normalize("   "));
        assertNull(AppSlugPolicy.normalize(null));
    }

    @Test
    void validation_shouldAcceptExpectedFormats() {
        assertTrue(AppSlugPolicy.isValidFormat("demo"));
        assertTrue(AppSlugPolicy.isValidFormat("ai-writer"));
        assertNull(AppSlugPolicy.validationMessage("demo"));
    }

    @Test
    void validation_shouldRejectInvalidFormatAndReservedWords() {
        assertFalse(AppSlugPolicy.isValidFormat("AI_Writer"));
        assertEquals("appSlug 仅允许小写字母、数字和短横线，且不能以短横线开头或结尾",
                AppSlugPolicy.validationMessage("AI_Writer"));
        assertEquals("appSlug 为系统保留词，请更换", AppSlugPolicy.validationMessage("runtime"));
        assertEquals("appSlug 长度必须在 3 到 40 个字符之间", AppSlugPolicy.validationMessage("ab"));
    }
}
