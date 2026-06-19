package cn.jualn.miniapp.third.wx.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * 微信接口通用返回基类。
 * <p>
 * 微信大部分接口在业务成功时返回字段不完全一致，但都会包含
 * {@code errcode}/{@code errmsg}。该基类用于统一成功态校验。
 * </p>
 */
@Data
public class WxApiResult {

    @JsonAlias("errcode")
    private Integer errcode;

    @JsonAlias("errmsg")
    private String errmsg;

    /**
     * 判断微信接口调用是否成功。
     *
     * @return {@code true} 表示 errcode 为空或为 0；否则为失败
     */
    public boolean isSuccess() {
        return errcode == null || errcode == 0;
    }

    /**
     * 判断是否为 access_token 失效类错误。
     *
     * @return {@code true} 表示 errcode 为 40001 或 42001
     */
    public boolean isTokenExpired() {
        return Integer.valueOf(40001).equals(errcode) || Integer.valueOf(42001).equals(errcode);
    }
}

