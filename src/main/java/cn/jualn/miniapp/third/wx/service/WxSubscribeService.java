package cn.jualn.miniapp.third.wx.service;

import cn.jualn.miniapp.module.wx.dto.WxBaseMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 服务号关注事件处理服务。TODO 暂未使用
 * 负责区分扫码关注、直接关注与已关注扫码三种场景。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxSubscribeService {

	private static final String QR_SCENE_PREFIX = "qrscene_";

	private final WxBindService wxBindService;

	/**
	 * subscribe 事件处理：
	 * 1) 扫码后关注：eventKey 形如 qrscene_xxx
	 * 2) 未扫码直接关注：eventKey 为空
	 *
	 * @param event 微信事件消息
	 */
	public void onSubscribe(WxBaseMessage event) {
		String eventKey = normalizeEventKey(event.getEventKey());
		String mpOpenid = event.getFromUserName();
		if (StringUtils.hasText(eventKey)) {
			wxBindService.handleScanBind(eventKey, mpOpenid, "subscribe");
			return;
		}

		log.info("收到微信直接关注事件，mpOpenid={}", mpOpenid);
		wxBindService.handleDirectSubscribe(mpOpenid);
	}

	/**
	 * scan 事件处理（用户已关注后再次扫码）。
	 *
	 * @param event 微信事件消息
	 */
	public void onScan(WxBaseMessage event) {
		String scene = normalizeEventKey(event.getEventKey());
		wxBindService.handleScanBind(scene, event.getFromUserName(), "scan");
	}

	/**
	 * 标准化 eventKey，移除 subscribe 回调中的 qrscene_ 前缀。
	 *
	 * @param eventKey 原始事件键
	 * @return 标准化后的 scene；为空时返回 null
	 */
	private String normalizeEventKey(String eventKey) {
		if (!StringUtils.hasText(eventKey)) {
			return null;
		}
		return eventKey.startsWith(QR_SCENE_PREFIX)
				? eventKey.substring(QR_SCENE_PREFIX.length())
				: eventKey;
	}

}
