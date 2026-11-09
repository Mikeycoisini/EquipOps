package com.common.response;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
/**
 * 统一 API 响应结果封装类
 *
 * @param <T> 响应数据的类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
// 当字段为 null 时，序列化为 JSON 时不包含该字段（比如失败时就不返回 data 了）
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;
    /**
     * 状态码：200 表示成功，其他表示失败
     */
    private Integer code;
    /**
     * 提示信息
     */
    private String message;
    /**
     * 响应数据
     */
    private T data;
    /**
     * 响应时间戳（可选，排查问题时非常有用）
     */
    private Long timestamp;
    // ==================== 成功时的静态工厂方法 ====================
    /**
     * 成功，无返回数据
     */
    public static <T> Result<T> success() {
        return new Result<>(200, "操作成功", null, System.currentTimeMillis());
    }
    /**
     * 成功，有返回数据
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "操作成功", data, System.currentTimeMillis());
    }
    /**
     * 成功，自定义提示信息 + 返回数据
     */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(200, message, data, System.currentTimeMillis());
    }
    // ==================== 失败时的静态工厂方法 ====================
    /**
     * 失败，默认错误码（500），自定义提示信息
     */
    public static <T> Result<T> fail(String message) {
        return new Result<>(500, message, null, System.currentTimeMillis());
    }
    /**
     * 失败，自定义错误码 + 自定义提示信息
     */
    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<>(code, message, null, System.currentTimeMillis());
    }
    /**
     * 失败，直接传入枚举（如果你后面有定义 ResultCode 枚举的话推荐用这个）
     */
    // public static <T> Result<T> fail(ResultCode resultCode) {
    //     return new Result<>(resultCode.getCode(), resultCode.getMessage(), null, System.currentTimeMillis());
    // }
}