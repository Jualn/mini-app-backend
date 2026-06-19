package cn.jualn.miniapp.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.common.result.ResultCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Objects;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ========== 参数校验类 ==========  -->  400

    // @Valid @Validated 校验失败（RequestBody）
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)   // 过滤null
                .findFirst()
                .orElse("参数错误");
        log.warn("参数校验失败: {}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.BAD_REQUEST, msg));
    }

    // @Validated 校验失败（RequestParam / PathVariable）
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<?>> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .orElse("参数错误");
        log.warn("约束校验失败: {}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.BAD_REQUEST, msg));
    }

    // 参数绑定失败（类型不匹配，如传了字母给 Integer）
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<?>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误: {} 期望类型 {}", e.getName(), e.getRequiredType());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.BAD_REQUEST, "参数类型错误：" + e.getName()));
    }

    // 必传参数缺失（@RequestParam 没传）
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<?>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必要参数: {}", e.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.BAD_REQUEST, "缺少必要参数：" + e.getParameterName()));
    }

    // 请求体解析失败（JSON 格式错误）
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<?>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.BAD_REQUEST, "请求体格式错误"));
    }

    // ========== SaToken 认证类 ==========

    // 未登录
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<?>> handleNotLogin(NotLoginException e) {
        log.warn("未登录访问: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.fail(ResultCode.UNAUTHORIZED, "未登录，请先登录"));
    }

    // 无权限
    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<Result<?>> handleNotPermission(NotPermissionException e) {
        log.warn("无权限访问: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.fail(ResultCode.FORBIDDEN, "无权限访问"));
    }

    // 角色不足
    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<Result<?>> handleNotRole(NotRoleException e) {
        log.warn("角色权限不足: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.fail(ResultCode.FORBIDDEN, "角色权限不足"));
    }

    // ========== 请求方式类 ==========

    // 请求方法不支持（POST 接口用 GET 请求）
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<?>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方式不支持: {}", e.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(ResultCode.METHOD_NOT_ALLOWED, "不支持 " + e.getMethod() + " 请求"));
    }

    // 媒体类型不支持（接口要 JSON，传了 form-data）
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<?>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("媒体类型不支持: {}", e.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Result.fail(ResultCode.UNSUPPORTED_MEDIA_TYPE, "不支持的媒体类型"));
    }

    // ========== 业务异常类 ==========

    // 1. 业务异常 → HTTP 200，code 用业务码
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<?>> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return ResponseEntity.ok(Result.fail(e.getCode(), e.getMessage()));
    }

    // 2. 系统异常 → HTTP 500，msg 对外固定，细节只打日志
    @ExceptionHandler(SystemException.class)
    public ResponseEntity<Result<?>> handleSystem(SystemException e) {
        log.error("系统异常: {}", e.getMessage(), e);   // 内部细节只在日志里
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ResultCode.SERVER_ERROR));
    }

    // 3. 兜底 Exception → 同样 500，但要完整打堆栈
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> handleException(Exception e) {
        log.error("未知异常", e); // ⚠️ 注意：这里要打完整堆栈，不能只打 message
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ResultCode.SERVER_ERROR));
    }
}
