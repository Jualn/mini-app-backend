package cn.jualn.miniapp.third.wx.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.HashMap;
import java.util.Map;

/**
 * 创建服务号临时二维码返回体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MpQrCodeCreateResponse extends WxApiResult {

    private String ticket;

    @JsonAlias("expire_seconds")
    private Integer expireSeconds;

    private String url;

    /**
     * 构建创建字符串场景临时二维码请求体。
     *
     * @param scene 场景值
     * @param expireSeconds 过期秒数
     * @return 微信接口请求体
     */
    public static Map<String, Object> buildRequest(String scene, int expireSeconds) {
        Map<String, Object> body = new HashMap<>();
        body.put("expire_seconds", expireSeconds);
        body.put("action_name", "QR_STR_SCENE");

        Map<String, Object> actionInfo = new HashMap<>();
        Map<String, Object> sceneObj = new HashMap<>();
        sceneObj.put("scene_str", scene);
        actionInfo.put("scene", sceneObj);
        body.put("action_info", actionInfo);
        return body;
    }
}

