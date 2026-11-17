package cn.apple.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, MissingServletRequestParameterException.class, MethodArgumentNotValidException.class})
    public ApiResponse<Void> handleBadRequest(Exception e) {
        return ApiResponse.error(message(e));
    }

    @ExceptionHandler({MaxUploadSizeExceededException.class, MultipartException.class})
    public ApiResponse<Void> handleUpload(Exception e) {
        return ApiResponse.error("上传文件不合法或超过限制");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("request failed", e);
        return ApiResponse.error("服务处理失败，请稍后再试");
    }

    private String message(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "请求参数不合法" : message;
    }
}
