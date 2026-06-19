package cn.jualn.miniapp.third.wx.controller;

import cn.jualn.miniapp.third.wx.dto.WxMpSubscribeResultDTO;
import cn.jualn.miniapp.third.wx.notice.WxMpNoticeTemplateView;
import cn.jualn.miniapp.third.wx.service.WxMpNoticeSubscribeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 服务号通知订阅入口结果接口。
 */
@RestController
@RequestMapping("/third/wx/mp-notice-subscribe")
@RequiredArgsConstructor
public class WxMpNoticeSubscribeController {

    private final WxMpNoticeSubscribeService wxMpNoticeSubscribeService;

    /**
     * H5 wx-open-subscribe 回传结果。
     */
    @PostMapping("/result")
    public void result(@RequestBody WxMpSubscribeResultDTO dto) {
        wxMpNoticeSubscribeService.recordResult(dto);
    }

    /**
     * 获取 H5 订阅页可展示的服务号通知模板。
     */
    @GetMapping("/templates")
    public List<WxMpNoticeTemplateView> templates() {
        return wxMpNoticeSubscribeService.listSubscribeTemplates();
    }
}