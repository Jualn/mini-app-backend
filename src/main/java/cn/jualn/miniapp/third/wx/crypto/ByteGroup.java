package cn.jualn.miniapp.third.wx.crypto;

import java.util.ArrayList;

/**
 * 字节拼接辅助容器。
 */
class ByteGroup {
	ArrayList<Byte> byteContainer = new ArrayList<Byte>();

	/**
	 * 输出当前容器中的字节数组。
	 *
	 * @return 字节数组
	 */
	public byte[] toBytes() {
		byte[] bytes = new byte[byteContainer.size()];
		for (int i = 0; i < byteContainer.size(); i++) {
			bytes[i] = byteContainer.get(i);
		}
		return bytes;
	}

	/**
	 * 追加字节数组。
	 *
	 * @param bytes 待追加字节
	 * @return 当前对象
	 */
	public ByteGroup addBytes(byte[] bytes) {
		for (byte b : bytes) {
			byteContainer.add(b);
		}
		return this;
	}

	/**
	 * 获取当前字节数量。
	 *
	 * @return 字节数量
	 */
	public int size() {
		return byteContainer.size();
	}
}
