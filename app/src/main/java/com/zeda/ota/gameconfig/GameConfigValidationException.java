package com.zeda.ota.gameconfig;

/**
 * 游戏配置校验异常，携带可直接上报的协议错误码。
 */
public class GameConfigValidationException extends Exception {

    private final String errorCode;

    public GameConfigValidationException(
            String errorCode,
            String message
    ) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
