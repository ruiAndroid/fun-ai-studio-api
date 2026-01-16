package fun.ai.studio.common;

import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidationExceptions(MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();
        List<FieldError> fieldErrors = bindingResult.getFieldErrors();
        String message = fieldErrors.stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return Result.error(message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<?> handleIllegalArgumentException(IllegalArgumentException e) {
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(WorkspaceNodeProxyException.class)
    public Result<?> handleWorkspaceNodeProxyException(WorkspaceNodeProxyException e) {
        // 这里统一返回 502：语义上更接近“上游（workspace-node）不可用/代理链路异常”
        return Result.error(502, e.getMessage());
    }

    @ExceptionHandler({MaxUploadSizeExceededException.class, MultipartException.class})
    public Result<?> handleUploadTooLarge(Exception e) {
        // Spring 默认会抛该异常；这里返回更清晰的提示（并用 413 语义化表示“请求实体过大”）
        return Result.error(413, "上传文件过大：请压缩后重试，或联系管理员提高上传上限（当前建议 <= 200MB）。");
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        return Result.error("系统错误：" + e.getMessage());
    }
} 